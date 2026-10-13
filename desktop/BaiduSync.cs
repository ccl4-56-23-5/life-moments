using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

// Desktop publishes immutable metadata snapshots; originals never enter this uploader.
internal sealed class BaiduSync : IDisposable
{
    readonly JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = 64 * 1024 * 1024 };
    readonly string credentialsPath;
    readonly HttpClient client;
    readonly Action<string> openAuthorization;
    readonly Func<int, CancellationToken, Task> waitAuthorization;
    Dictionary<string, object> config = new Dictionary<string, object>();
    internal BaiduSync(string root, HttpMessageHandler handler = null, Action<string> openAuthorization = null, Func<int, CancellationToken, Task> waitAuthorization = null)
    {
        credentialsPath = Path.Combine(root, "baidu-credentials.bin");
        client = handler == null ? new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) : new HttpClient(handler);
        this.openAuthorization = openAuthorization ?? (url => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }));
        this.waitAuthorization = waitAuthorization ?? ((milliseconds, token) => Task.Delay(milliseconds, token));
        client.Timeout = TimeSpan.FromSeconds(90); client.DefaultRequestHeaders.UserAgent.ParseAdd("LifeMoments/0.3.0-beta.8");
        if (File.Exists(credentialsPath)) config = json.Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(credentialsPath), null, DataProtectionScope.CurrentUser)));
    }
    internal string AppKey { get { return Value("appKey"); } }
    internal string Secret { get { return Value("secret"); } }
    internal string Folder { get { return Value("folder"); } }
    internal string InputAppKey { get { return config.ContainsKey("draftAppKey") ? Value("draftAppKey") : AppKey; } }
    internal string InputSecret { get { return config.ContainsKey("draftSecret") ? Value("draftSecret") : Secret; } }
    internal string InputFolder { get { return config.ContainsKey("draftFolder") ? Value("draftFolder") : Folder; } }
    internal string LastSync { get { return Value("lastSync"); } }
    internal bool Connected { get { return Value("accessToken").Length > 0 && AppKey.Length > 0 && Secret.Length > 0 && Folder.Length > 0; } }
    string Value(string key) { return Convert.ToString(PhotoWindow.Get(config, key, "")); }
    internal void SaveDraft(string key, string secret, string folder)
    {
        if (config.ContainsKey("draftAppKey") && InputAppKey == key && InputSecret == secret && InputFolder == folder) return;
        config["draftAppKey"] = key; config["draftSecret"] = secret; config["draftFolder"] = folder;
        Save();
    }
    internal static string ValidateFolder(string value)
    {
        string path = value.Trim().TrimEnd('/'); string[] segments = path.Split('/');
        if (!path.StartsWith("/apps/") || segments.Length < 4 || segments.Skip(2).Any(s => s.Length == 0 || s == "." || s == ".." || s.IndexOfAny(new[] { '\\', ':', '*', '?', '"', '<', '>', '|', '\r', '\n' }) >= 0)) throw new InvalidOperationException("索引目录应为 /apps/你的应用名称/生活点滴索引。");
        return path;
    }
    internal async Task AuthorizeAsync(string key, string secret, string folder, Action<string> display, CancellationToken token)
    {
        token.ThrowIfCancellationRequested(); SaveDraft(key, secret, folder);
        folder = ValidateFolder(folder); if (string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(secret)) throw new InvalidOperationException("请填写百度网盘开放平台应用的 AppKey 和 SecretKey。");
        var device = await GetOAuthAsync("https://openapi.baidu.com/oauth/2.0/device/code", Params("response_type", "device_code", "client_id", key.Trim(), "scope", "basic,netdisk"), token);
        Check(device); string code = Convert.ToString(device["device_code"]), userCode = Convert.ToString(device["user_code"]), verification = Convert.ToString(device["verification_url"]);
        Uri uri; if (!Uri.TryCreate(verification, UriKind.Absolute, out uri) || uri.Scheme != "https" || !(uri.Host == "baidu.com" || uri.Host.EndsWith(".baidu.com"))) throw new InvalidOperationException("百度返回的授权地址无效。");
        display("在浏览器中登录百度账号，输入授权码：" + userCode); openAuthorization(verification);
        int interval = Math.Max(3, Convert.ToInt32(PhotoWindow.Get(device, "interval", 5))); var deadline = DateTime.UtcNow.AddSeconds(Convert.ToInt32(PhotoWindow.Get(device, "expires_in", 600)));
        while (DateTime.UtcNow < deadline)
        {
            await waitAuthorization(interval * 1000, token);
            token.ThrowIfCancellationRequested();
            var result = await GetOAuthAsync("https://openapi.baidu.com/oauth/2.0/token", Params("grant_type", "device_token", "code", code, "client_id", key.Trim(), "client_secret", secret.Trim()), token);
            string error = Convert.ToString(PhotoWindow.Get(result, "error", ""));
            if (error == "authorization_pending") continue; if (error == "slow_down") { interval += 5; continue; }
            Check(result); if (!result.ContainsKey("access_token") || string.IsNullOrWhiteSpace(Convert.ToString(result["access_token"]))) throw new InvalidOperationException("百度授权未返回访问令牌。");
            token.ThrowIfCancellationRequested();
            config = new Dictionary<string, object> { { "appKey", key.Trim() }, { "secret", secret.Trim() }, { "folder", folder } }; ApplyTokens(result); Save(); return;
        }
        throw new InvalidOperationException("授权已超时，请重试。");
    }
    internal void UpdateFolder(string value) { string folder = ValidateFolder(value); if (folder != Folder) { config.Remove("lastDigest"); config.Remove("lastSync"); config.Remove("lastRemotePath"); } config["folder"] = folder; Save(); }
    internal void Disconnect() { config.Remove("accessToken"); config.Remove("refreshToken"); config.Remove("lastSync"); Save(); }
    void ApplyTokens(Dictionary<string, object> data)
    {
        config["accessToken"] = data["access_token"]; if (data.ContainsKey("refresh_token")) config["refreshToken"] = data["refresh_token"];
        config["expiresAt"] = DateTime.UtcNow.AddSeconds(Convert.ToDouble(PhotoWindow.Get(data, "expires_in", 2592000)) - 300).ToString("o");
    }
    async Task<string> TokenAsync(CancellationToken token)
    {
        DateTime expires; if (!DateTime.TryParse(Value("expiresAt"), null, System.Globalization.DateTimeStyles.RoundtripKind, out expires) || expires.ToUniversalTime() <= DateTime.UtcNow)
        {
            if (Value("refreshToken").Length == 0) throw new InvalidOperationException("百度授权已过期，请重新连接账号。");
            var data = await GetOAuthAsync("https://openapi.baidu.com/oauth/2.0/token", Params("grant_type", "refresh_token", "refresh_token", Value("refreshToken"), "client_id", AppKey, "client_secret", Secret), token);
            Check(data); ApplyTokens(data); Save();
        }
        return Value("accessToken");
    }
    internal async Task<bool> UploadAsync(byte[] snapshot, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if (!Connected) throw new InvalidOperationException("请先连接百度网盘。");
        string digest = Digest(snapshot); if (Value("lastDigest") == digest) return false;
        string access = await TokenAsync(token);
        string folder = ValidateFolder(Folder); string[] segments = folder.Split('/'); string parent = "/apps/" + segments[2];
        for (int i = 3; i < segments.Length; i++)
        {
            token.ThrowIfCancellationRequested(); parent += "/" + segments[i];
            var mkdir = await PostAsync("https://pan.baidu.com/rest/2.0/xpan/file?method=create&access_token=" + Uri.EscapeDataString(access), new FormUrlEncodedContent(Params("path", parent, "isdir", "1", "rtype", "0")), token);
            if (Convert.ToInt32(PhotoWindow.Get(mkdir, "errno", 0)) != -8) Check(mkdir);
        }
        // Unique revision names avoid replacing unrelated files or a previously usable index.
        string path = folder + "/index-" + DateTime.UtcNow.ToString("yyyyMMddTHHmmssfff") + "-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".json";
        var blocks = Split(snapshot); string blockList = json.Serialize(blocks.Select(Md5).ToArray());
        var fields = Params("path", path, "size", snapshot.Length.ToString(System.Globalization.CultureInfo.InvariantCulture), "isdir", "0", "autoinit", "1", "rtype", "0", "block_list", blockList);
        var pre = await PostAsync("https://pan.baidu.com/rest/2.0/xpan/file?method=precreate&access_token=" + Uri.EscapeDataString(access), new FormUrlEncodedContent(fields), token); Check(pre);
        if (Convert.ToInt32(PhotoWindow.Get(pre, "return_type", 1)) != 2)
        {
            string uploadId = Convert.ToString(pre["uploadid"]);
            for (int i = 0; i < blocks.Count; i++)
            {
                token.ThrowIfCancellationRequested();
                using (var multipart = new MultipartFormDataContent())
                {
                    multipart.Add(new ByteArrayContent(blocks[i]), "file", "index-part.json");
                    string url = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2?" + Query(Params("method", "upload", "access_token", access, "type", "tmpfile", "path", path, "uploadid", uploadId, "partseq", i.ToString()));
                    var part = await PostAsync(url, multipart, token); Check(part);
                    if (Convert.ToString(PhotoWindow.Get(part, "md5", "")) != Md5(blocks[i])) throw new InvalidOperationException("百度分片校验失败，索引未发布。");
                }
            }
            token.ThrowIfCancellationRequested(); fields["uploadid"] = uploadId; fields.Remove("autoinit");
            var created = await PostAsync("https://pan.baidu.com/rest/2.0/xpan/file?method=create&access_token=" + Uri.EscapeDataString(access), new FormUrlEncodedContent(fields), token); Check(created);
            if (!created.ContainsKey("fs_id")) throw new InvalidOperationException("百度未确认索引创建成功。");
        }
        config["lastSync"] = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"); config["lastRemotePath"] = path; config["lastDigest"] = digest; Save(); return true;
    }
    string Digest(byte[] snapshot)
    {
        // Ignore publication time; unchanged metadata must not create another remote version on every launch.
        byte[] content = snapshot;
        try { var parsed = json.Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(snapshot)); parsed.Remove("createdAt"); content = Encoding.UTF8.GetBytes(json.Serialize(parsed)); } catch { }
        using (var sha = SHA256.Create()) return string.Concat(sha.ComputeHash(content).Select(b => b.ToString("x2")));
    }
    internal static List<byte[]> Split(byte[] value)
    {
        var blocks = new List<byte[]>(); const int size = 4 * 1024 * 1024;
        for (int offset = 0; offset < value.Length; offset += size) { var block = new byte[Math.Min(size, value.Length - offset)]; Buffer.BlockCopy(value, offset, block, 0, block.Length); blocks.Add(block); }
        if (blocks.Count == 0) throw new InvalidOperationException("索引内容为空。"); return blocks;
    }
    internal static string Md5(byte[] data) { using (var md5 = MD5.Create()) return string.Concat(md5.ComputeHash(data).Select(b => b.ToString("x2"))); }
    internal static Dictionary<string, string> Params(params string[] pairs) { var result = new Dictionary<string, string>(); for (int i = 0; i < pairs.Length; i += 2) result[pairs[i]] = pairs[i + 1]; return result; }
    static string Query(Dictionary<string, string> values) { return string.Join("&", values.Select(kv => Uri.EscapeDataString(kv.Key) + "=" + Uri.EscapeDataString(kv.Value))); }
    async Task<Dictionary<string, object>> GetOAuthAsync(string url, Dictionary<string, string> query, CancellationToken token) { using (var response = await client.GetAsync(url + "?" + Query(query), token)) return await ReadAsync(response, true); }
    async Task<Dictionary<string, object>> PostAsync(string url, HttpContent body, CancellationToken token)
    {
        using (body) using (var response = await client.PostAsync(url, body, token))
        {
            try { return await ReadAsync(response); }
            catch (InvalidOperationException e)
            {
                string stage = url.Contains("/superfile2?") ? "分片上传" : url.Contains("method=precreate") ? "预创建索引" : "创建目录或索引";
                throw new InvalidOperationException(stage + "失败：" + e.Message, e);
            }
        }
    }
    async Task<Dictionary<string, object>> ReadAsync(HttpResponseMessage response, bool oauth = false)
    {
        Dictionary<string, object> result = null;
        // PCS returns JSON with "text/html; charset=utf8". Framework HttpContent
        // rejects that charset alias in ReadAsStringAsync; decode JSON bytes as UTF-8.
        byte[] responseBytes = await response.Content.ReadAsByteArrayAsync();
        try { result = json.Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(responseBytes).TrimStart('\uFEFF')); }
        catch (ArgumentException) { }
        catch (InvalidOperationException) { }
        // OAuth uses HTTP 400 for pending/slow_down and terminal authorization errors.
        // Only this structured OAuth error reaches the poller; upload failures stay failures.
        if (oauth && response.StatusCode == System.Net.HttpStatusCode.BadRequest && result != null && !string.IsNullOrWhiteSpace(Convert.ToString(PhotoWindow.Get(result, "error", "")))) return result;
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException("百度接口请求失败（HTTP " + (int)response.StatusCode + "），请稍后重试。");
        if (result == null) throw new InvalidOperationException("百度接口响应格式无效，请稍后重试。");
        return result;
    }
    static void Check(Dictionary<string, object> result)
    {
        string error = Convert.ToString(PhotoWindow.Get(result, "error", "")); int number = Convert.ToInt32(PhotoWindow.Get(result, "errno", PhotoWindow.Get(result, "error_code", 0)));
        if (error == "invalid_client") throw new InvalidOperationException("百度应用凭据无效（invalid_client），请检查同一应用的 AppKey 和 SecretKey。");
        if (error == "unauthorized_client") throw new InvalidOperationException("百度应用未获授权（unauthorized_client），请检查应用状态及接口权限。");
        if (error == "access_denied" || error == "authorization_declined") throw new InvalidOperationException("百度授权被拒绝（" + error + "），请重新授权并确认允许访问。");
        if (error == "expired_token" || error == "code_expired") throw new InvalidOperationException("百度授权码已过期（" + error + "），请点击授权连接获取新码。");
        if (error.Length > 0 || number != 0) throw new InvalidOperationException("百度接口返回错误：" + (error.Length > 0 ? error : number.ToString()) + "。请检查应用权限、授权及索引目录后重试。");
    }
    void Save()
    {
        byte[] value = ProtectedData.Protect(Encoding.UTF8.GetBytes(json.Serialize(config)), null, DataProtectionScope.CurrentUser); string temp = credentialsPath + ".tmp";
        File.WriteAllBytes(temp, value); if (File.Exists(credentialsPath)) File.Replace(temp, credentialsPath, null); else File.Move(temp, credentialsPath);
    }
    internal void SetTestCredentials() { config = new Dictionary<string, object> { { "appKey", "test-key" }, { "secret", "test-secret" }, { "folder", "/apps/test/生活点滴索引" }, { "accessToken", "test-token" }, { "expiresAt", DateTime.UtcNow.AddDays(1).ToString("o") } }; }
    public void Dispose() { client.Dispose(); }
}

internal sealed class CloudSettingsDialog : Form
{
    readonly BaiduSync cloud; readonly CancellationTokenSource cancel = new CancellationTokenSource();
    readonly TextBox key = new TextBox(), secret = new TextBox(), folder = new TextBox(); readonly Label status = new Label(), state = new Label();
    readonly SyncButton connect = new SyncButton(true), save = new SyncButton(false);
    readonly SyncCard statusBadge = new SyncCard();
    static readonly Color Background = Color.FromArgb(245, 245, 247), Foreground = Color.FromArgb(32, 33, 37), Muted = Color.FromArgb(124, 125, 133), Blue = Color.FromArgb(0, 122, 255);
    internal CloudSettingsDialog(BaiduSync value)
    {
        cloud = value; Text = "百度网盘同步 · Beta"; AutoScaleDimensions = new SizeF(96, 96); AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(620, 612); StartPosition = FormStartPosition.CenterParent; FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; MinimizeBox = false; ShowInTaskbar = false;
        Font = new Font("Microsoft YaHei UI", 9.5f); BackColor = Background; ForeColor = Foreground;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24), ColumnCount = 1, RowCount = 5, Margin = Padding.Empty };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        foreach (int height in new[] { 82, 256, 42, 108 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); Controls.Add(layout);
        var header = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3, Margin = Padding.Empty };
        header.RowStyles.Add(new RowStyle(SizeType.Absolute, 16)); header.RowStyles.Add(new RowStyle(SizeType.Absolute, 35)); header.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        header.Controls.Add(MakeLabel("百度网盘", Blue, 9, FontStyle.Bold), 0, 0);
        header.Controls.Add(MakeLabel("索引同步", Foreground, 22, FontStyle.Bold), 0, 1);
        header.Controls.Add(MakeLabel("同步人物、相册、批注、头像选框与位置校准", Muted, 9.5f, FontStyle.Regular), 0, 2); layout.Controls.Add(header, 0, 0);
        var fieldsCard = new SyncCard { Dock = DockStyle.Fill, Padding = new Padding(20), Margin = Padding.Empty };
        var fields = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3, Margin = Padding.Empty };
        fields.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (int i = 0; i < 3; i++) fields.RowStyles.Add(new RowStyle(SizeType.Percent, 33.333f));
        key.Text = cloud.InputAppKey; key.AccessibleName = "AppKey";
        secret.UseSystemPasswordChar = true; secret.Text = cloud.InputSecret; secret.AccessibleName = "SecretKey";
        folder.Text = cloud.InputFolder.Length > 0 ? cloud.InputFolder : "/apps/你的应用名称/生活点滴索引"; folder.AccessibleName = "云端索引目录";
        AddField(fields, "AppKey", key, 0); AddField(fields, "SecretKey", secret, 1); AddField(fields, "云端索引目录", folder, 2);
        fieldsCard.Controls.Add(fields); layout.Controls.Add(fieldsCard, 0, 1);
        var hint = MakeLabel("每次同步保留一个完整索引版本。原照片由现有备份流程上传。", Muted, 9, FontStyle.Regular); hint.Padding = new Padding(3, 7, 3, 0); layout.Controls.Add(hint, 0, 2);
        var statusCard = new SyncCard { Dock = DockStyle.Fill, Padding = new Padding(16, 13, 16, 13), Margin = Padding.Empty };
        var statusLayout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2, Margin = Padding.Empty };
        statusLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 26)); statusLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        statusBadge.Size = new Size(84, 24); statusBadge.Radius = 7; statusBadge.Margin = Padding.Empty; statusBadge.BorderColor = Color.Transparent;
        state.Dock = DockStyle.Fill; state.BackColor = Color.Transparent; state.TextAlign = ContentAlignment.MiddleCenter; state.Font = new Font(Font.FontFamily, 8.5f, FontStyle.Bold); statusBadge.Controls.Add(state); statusLayout.Controls.Add(statusBadge, 0, 0);
        status.Dock = DockStyle.Fill; status.ForeColor = Muted; status.TextAlign = ContentAlignment.MiddleLeft; status.Margin = new Padding(0, 5, 0, 0); status.Font = new Font(Font.FontFamily, 9.5f); statusLayout.Controls.Add(status, 0, 1);
        statusCard.Controls.Add(statusLayout); layout.Controls.Add(statusCard, 0, 3);
        SetStatus(cloud.Connected ? "已连接" : "未连接", cloud.Connected ? "凭据已使用 Windows 当前用户加密保存。返回相册后可开启同步。" : "填写应用信息后点击授权，在浏览器中登录百度账号。", cloud.Connected ? Color.FromArgb(36, 155, 72) : Muted);
        var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1, Margin = Padding.Empty, Padding = new Padding(0, 17, 0, 0) };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 124)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 126));
        var portal = new LinkLabel { Text = "百度网盘开放平台 ↗", Dock = DockStyle.Fill, LinkColor = Blue, ActiveLinkColor = Blue, VisitedLinkColor = Blue, LinkBehavior = LinkBehavior.HoverUnderline, TextAlign = ContentAlignment.MiddleLeft, Margin = Padding.Empty, Font = new Font(Font.FontFamily, 9) };
        portal.LinkClicked += delegate { Process.Start(new ProcessStartInfo("https://pan.baidu.com/union") { UseShellExecute = true }); }; footer.Controls.Add(portal, 0, 0);
        connect.Text = cloud.Connected ? "重新授权" : "授权连接"; connect.Anchor = AnchorStyles.Left | AnchorStyles.Right; connect.Size = new Size(114, 44); connect.Margin = new Padding(0, 0, 10, 0); connect.TabIndex = 3; footer.Controls.Add(connect, 1, 0);
        save.Text = "保存目录"; save.Anchor = AnchorStyles.Left | AnchorStyles.Right; save.Size = new Size(126, 44); save.Margin = Padding.Empty; save.Enabled = cloud.Connected; save.TabIndex = 4; footer.Controls.Add(save, 2, 0); layout.Controls.Add(footer, 0, 4);
        ActiveControl = key;
        connect.Click += async delegate
        {
            connect.Enabled = save.Enabled = false; connect.Text = "连接中…"; SetStatus("正在连接", "正在获取百度授权信息…", Blue);
            try { await cloud.AuthorizeAsync(key.Text, secret.Text, folder.Text, text => SetStatus("等待授权", text, Blue), cancel.Token); SetStatus("已连接", "授权成功。返回相册后开启索引同步。", Color.FromArgb(36, 155, 72)); save.Enabled = true; }
            catch (OperationCanceledException) { }
            catch (Exception e) { if (!IsDisposed) SetStatus("连接失败", e.Message, Color.FromArgb(208, 104, 20)); }
            finally { if (!IsDisposed) { connect.Enabled = true; connect.Text = cloud.Connected ? "重新授权" : "授权连接"; } }
        };
        save.Click += delegate { try { cloud.UpdateFolder(folder.Text); DialogResult = DialogResult.OK; Close(); } catch (Exception e) { SetStatus("目录无效", e.Message, Color.FromArgb(208, 104, 20)); } };
        FormClosing += delegate(object sender, FormClosingEventArgs e)
        {
            cancel.Cancel();
            try { cloud.SaveDraft(key.Text, secret.Text, folder.Text); }
            catch (Exception) { e.Cancel = true; SetStatus("保存失败", "填写内容未能保存到本机，请检查磁盘空间和用户数据目录权限。", Color.FromArgb(208, 104, 20)); }
        };
    }
    static Label MakeLabel(string text, Color color, float size, FontStyle style) { return new Label { Text = text, Dock = DockStyle.Fill, Margin = Padding.Empty, ForeColor = color, Font = new Font("Microsoft YaHei UI", size, style), TextAlign = ContentAlignment.MiddleLeft }; }
    static void AddField(TableLayoutPanel parent, string name, TextBox input, int row)
    {
        var field = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2, Margin = new Padding(0, 0, 0, row == 2 ? 0 : 6) };
        field.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); field.RowStyles.Add(new RowStyle(SizeType.Absolute, 23)); field.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        field.Controls.Add(MakeLabel(name, Foreground, 9, FontStyle.Regular), 0, 0);
        input.Font = new Font("Microsoft YaHei UI", 10); input.TabIndex = row; field.Controls.Add(new SyncTextField(input) { Dock = DockStyle.Fill, Margin = Padding.Empty }, 0, 1); parent.Controls.Add(field, 0, row);
    }
    void SetStatus(string title, string text, Color color)
    {
        if (IsDisposed) return; state.Text = title; state.ForeColor = color; status.Text = text;
        statusBadge.BackColor = Color.FromArgb(242 + (color.R - 242) / 10, 242 + (color.G - 242) / 10, 247 + (color.B - 247) / 10); statusBadge.Invalidate();
    }
    // These controls retain native text editing, focus, keyboard and accessibility behavior.
    // Only their surfaces are drawn to match the WebView's iOS-style cards and controls.
    sealed class SyncCard : Panel
    {
        internal int Radius = 16; internal Color BorderColor = Color.FromArgb(231, 231, 236);
        internal SyncCard() { BackColor = Color.White; SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true); }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            e.Graphics.Clear(Parent == null ? Background : Parent.BackColor); e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (var path = RoundPath(new RectangleF(.5f, .5f, Math.Max(1, Width - 1), Math.Max(1, Height - 1)), Radius * DeviceDpi / 96f))
            using (var fill = new SolidBrush(BackColor)) { e.Graphics.FillPath(fill, path); if (BorderColor.A > 0) using (var pen = new Pen(BorderColor)) e.Graphics.DrawPath(pen, path); }
        }
    }
    sealed class SyncTextField : Panel
    {
        readonly TextBox input;
        internal SyncTextField(TextBox value)
        {
            input = value; BackColor = Background; input.BorderStyle = BorderStyle.None; input.BackColor = BackColor; input.ForeColor = Foreground; Controls.Add(input);
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            input.GotFocus += delegate { Invalidate(); }; input.LostFocus += delegate { Invalidate(); }; Click += delegate { input.Focus(); };
        }
        protected override void OnLayout(LayoutEventArgs e) { base.OnLayout(e); int inset = (int)Math.Round(12 * DeviceDpi / 96f); input.SetBounds(inset, Math.Max(0, (Height - input.PreferredHeight) / 2), Math.Max(1, Width - 2 * inset), input.PreferredHeight); }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            e.Graphics.Clear(Parent == null ? Color.White : Parent.BackColor); e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (var path = RoundPath(new RectangleF(1, 1, Math.Max(1, Width - 2), Math.Max(1, Height - 2)), 10 * DeviceDpi / 96f))
            using (var fill = new SolidBrush(BackColor)) using (var pen = new Pen(input.Focused ? Blue : Color.FromArgb(231, 231, 236), input.Focused ? 1.6f : 1)) { e.Graphics.FillPath(fill, path); e.Graphics.DrawPath(pen, path); }
        }
    }
    sealed class SyncButton : Button
    {
        readonly bool primary; bool hovered, pressed;
        internal SyncButton(bool value) { primary = value; FlatStyle = FlatStyle.Flat; FlatAppearance.BorderSize = 0; Cursor = Cursors.Hand; Font = new Font("Microsoft YaHei UI", 9.5f, FontStyle.Bold); SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true); }
        protected override void OnMouseEnter(EventArgs e) { hovered = true; base.OnMouseEnter(e); Invalidate(); }
        protected override void OnMouseLeave(EventArgs e) { hovered = pressed = false; base.OnMouseLeave(e); Invalidate(); }
        protected override void OnMouseDown(MouseEventArgs e) { pressed = true; base.OnMouseDown(e); Invalidate(); }
        protected override void OnMouseUp(MouseEventArgs e) { pressed = false; base.OnMouseUp(e); Invalidate(); }
        protected override void OnGotFocus(EventArgs e) { base.OnGotFocus(e); Invalidate(); }
        protected override void OnLostFocus(EventArgs e) { base.OnLostFocus(e); Invalidate(); }
        protected override void OnEnabledChanged(EventArgs e) { base.OnEnabledChanged(e); Invalidate(); }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(Parent == null ? Background : Parent.BackColor); e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Color fill = !Enabled ? Color.FromArgb(232, 232, 237) : primary ? (pressed ? Color.FromArgb(0, 105, 225) : hovered ? Color.FromArgb(20, 133, 255) : Blue) : hovered ? Color.FromArgb(231, 240, 255) : Color.White;
            using (var path = RoundPath(new RectangleF(1, 1, Math.Max(1, Width - 2), Math.Max(1, Height - 2)), 11 * DeviceDpi / 96f))
            using (var brush = new SolidBrush(fill)) { e.Graphics.FillPath(brush, path); if (!primary && Enabled) using (var pen = new Pen(Color.FromArgb(226, 226, 232))) e.Graphics.DrawPath(pen, path); }
            if (Focused && ShowFocusCues) using (var path = RoundPath(new RectangleF(4, 4, Math.Max(1, Width - 8), Math.Max(1, Height - 8)), 8 * DeviceDpi / 96f)) using (var pen = new Pen(primary ? Color.White : Blue, 1)) e.Graphics.DrawPath(pen, path);
            TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, !Enabled ? Color.FromArgb(155, 155, 163) : primary ? Color.White : Blue, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix);
        }
    }
    static GraphicsPath RoundPath(RectangleF rect, float radius)
    {
        var path = new GraphicsPath(); float d = Math.Min(radius * 2, Math.Min(rect.Width, rect.Height));
        path.AddArc(rect.X, rect.Y, d, d, 180, 90); path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90); path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90); path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90); path.CloseFigure(); return path;
    }
}
