using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    [STAThread] static void Main(string[] args) { Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new PhotoWindow(args)); }
}
internal sealed partial class PhotoWindow : Form
{
    const string AppOrigin = "https://app.life-moments.local/";
    readonly JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = 64 * 1024 * 1024 };
    readonly string dataRoot, testSource;
    readonly bool selfTest;
    readonly WebView2 web = new WebView2();
    readonly LoadingScreen loading = new LoadingScreen();
    readonly Stopwatch startup = Stopwatch.StartNew();
    long pageReadyMs, mainShownMs;
    readonly SemaphoreSlim thumbnails = new SemaphoreSlim(2);
    readonly SemaphoreSlim fullImages = new SemaphoreSlim(1);
    readonly ConcurrentDictionary<string, Lazy<Task<byte[]>>> imageRequests = new ConcurrentDictionary<string, Lazy<Task<byte[]>>>();
    readonly System.Windows.Forms.Timer syncTimer = new System.Windows.Forms.Timer { Interval = 15000 };
    PhotoLibrary library; BaiduSync cloud;
    Dictionary<string, object> settings, index, photoData;
    Dictionary<string, Dictionary<string, object>> photosById = new Dictionary<string, Dictionary<string, object>>();
    string sourceRoot, scriptId, session, cloudState = "";
    int generation, syncRevision;
    bool choosing, closing, ready, testStarted, busy, syncBusy;
    CancellationTokenSource scanCancel = new CancellationTokenSource(), syncCancel = new CancellationTokenSource();
    Task scanTask = Task.FromResult(0);
    public PhotoWindow(string[] args)
    {
        selfTest = args.Contains("--self-test"); testSource = Arg(args, "--source"); string testRoot = Arg(args, "--test-data");
        dataRoot = selfTest && testRoot != null ? Path.GetFullPath(testRoot) : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LifeMoments");
        Directory.CreateDirectory(dataRoot); Text = "生活点滴 · Beta"; ClientSize = new Size(1280, 820); MinimumSize = new Size(900, 600);
        StartPosition = FormStartPosition.CenterScreen; Icon = new Icon(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico"));
        if (selfTest) { ShowInTaskbar = false; Opacity = 0; }
        web.Dock = loading.Dock = DockStyle.Fill; Controls.Add(web); Controls.Add(loading);
        syncTimer.Tick += async delegate { syncTimer.Stop(); await SynchronizeAsync(); };
        Shown += async delegate { loading.BeginCycle(true); await InitializeAsync(); };
        FormClosing += async delegate(object sender, FormClosingEventArgs e)
        {
            if (closing) return; e.Cancel = true; closing = true; syncTimer.Stop(); syncCancel.Cancel(); scanCancel.Cancel();
            try { if (ready) { await web.CoreWebView2.ExecuteScriptAsync("window.dispatchEvent(new Event('desktop:save'))"); await Task.Delay(150); } await scanTask; } catch { }
            Close();
        };
        FormClosed += delegate { web.Dispose(); loading.Dispose(); syncTimer.Dispose(); if (cloud != null) cloud.Dispose(); };
    }
    static string Arg(string[] a, string n) { int i = Array.IndexOf(a, n); return i >= 0 && i + 1 < a.Length ? a[i + 1] : null; }
    async Task InitializeAsync()
    {
        try
        {
            settings = File.Exists(Path.Combine(dataRoot, "settings.json")) ? json.Deserialize<Dictionary<string, object>>(File.ReadAllText(Path.Combine(dataRoot, "settings.json"))) : new Dictionary<string, object>();
            sourceRoot = Convert.ToString(Get(settings, "source", Environment.GetFolderPath(Environment.SpecialFolder.MyPictures))); if (selfTest && testSource != null) sourceRoot = testSource;
            if (!Directory.Exists(sourceRoot)) sourceRoot = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
            cloud = new BaiduSync(dataRoot); string initialSource = sourceRoot;
            var discover = Task.Run(() => { var prepared = new PhotoLibrary(dataRoot); return Tuple.Create(prepared, prepared.Discover(initialSource)); });
            var options = new CoreWebView2EnvironmentOptions(); if (selfTest) options.AdditionalBrowserArguments = "--disable-backgrounding-occluded-windows --disable-renderer-backgrounding --disable-background-timer-throttling";
            await web.EnsureCoreWebView2Async(await CoreWebView2Environment.CreateAsync(null, Path.Combine(dataRoot, "WebView2"), options));
            if (closing || IsDisposed) return;
            web.CoreWebView2.Settings.AreDevToolsEnabled = selfTest; web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            web.CoreWebView2.Settings.IsStatusBarEnabled = false; web.CoreWebView2.Settings.IsZoomControlEnabled = false; web.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
            web.CoreWebView2.SetVirtualHostNameToFolderMapping("app.life-moments.local", Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app"), CoreWebView2HostResourceAccessKind.Deny);
            web.CoreWebView2.PermissionRequested += delegate(object s, CoreWebView2PermissionRequestedEventArgs e) { e.State = CoreWebView2PermissionState.Deny; };
            web.CoreWebView2.NewWindowRequested += delegate(object s, CoreWebView2NewWindowRequestedEventArgs e) { e.Handled = true; OpenExternal(e.Uri); };
            web.CoreWebView2.NavigationStarting += delegate(object s, CoreWebView2NavigationStartingEventArgs e) { if (!e.Uri.StartsWith(AppOrigin, StringComparison.OrdinalIgnoreCase)) { e.Cancel = true; OpenExternal(e.Uri); } };
            web.CoreWebView2.DownloadStarting += delegate(object s, CoreWebView2DownloadStartingEventArgs e) { e.Cancel = true; };
            web.CoreWebView2.WebMessageReceived += OnMessage;
            web.CoreWebView2.AddWebResourceRequestedFilter("https://thumbs.life-moments.local/*", CoreWebView2WebResourceContext.Image); web.CoreWebView2.WebResourceRequested += OnThumbnail;
            web.CoreWebView2.AddWebResourceRequestedFilter("https://rendered.life-moments.local/*", CoreWebView2WebResourceContext.Image);
            web.CoreWebView2.NavigationCompleted += async delegate(object s, CoreWebView2NavigationCompletedEventArgs e)
            {
                if (closing || web.IsDisposed) return;
                ready = e.IsSuccess;
                if (!ready) { loading.Status = "页面加载失败，请重新启动"; return; }
                int loadedGeneration = generation;
                pageReadyMs = startup.ElapsedMilliseconds;
                photoData["startupMs"] = pageReadyMs; await SendStatusAsync();
                if (closing || loadedGeneration != generation) return;
                BeginMetadataScan();
                await loading.CompleteAsync();
                if (closing || loadedGeneration != generation || !ready) return;
                loading.Visible = false; mainShownMs = startup.ElapsedMilliseconds;
                await web.CoreWebView2.ExecuteScriptAsync("window.desktopReveal?.()");
                if (closing || loadedGeneration != generation) return;
                if (selfTest && !testStarted) { testStarted = true; await TestAsync(); }
            };
            var preparedLibrary = await discover; if (closing || IsDisposed) return; library = preparedLibrary.Item1;
            await LoadFolderAsync(sourceRoot, preparedLibrary.Item2);
        }
        catch (Exception e) { Log(e.ToString()); loading.Status = "启动失败：" + e.Message; if (selfTest) { File.WriteAllText(Path.Combine(dataRoot, "self-test-error.txt"), e.ToString()); Close(); } }
    }
    async Task LoadFolderAsync(string folder, Dictionary<string, object> discovered = null)
    {
        if (!Directory.Exists(folder)) throw new DirectoryNotFoundException("照片目录不存在，请重新选择。");
        busy = true; ready = false; scanCancel.Cancel(); await scanTask;
        if (closing || web.IsDisposed) return;
        sourceRoot = Path.GetFullPath(folder).TrimEnd(Path.DirectorySeparatorChar); generation++; session = Guid.NewGuid().ToString("N");
        if (generation > 1) loading.BeginCycle(false);
        loading.Visible = true; loading.BringToFront(); loading.Status = "正在打开照片目录…";
        try
        {
            var discoveredPhotos = discovered ?? await Task.Run(() => library.Discover(sourceRoot));
            if (closing || web.IsDisposed) return;
            SetPhotoData(discoveredPhotos); index = await Task.Run(() => library.LoadIndex(sourceRoot));
            if (closing || web.IsDisposed) return;
            settings["source"] = sourceRoot; SaveSettings();
            web.CoreWebView2.SetVirtualHostNameToFolderMapping("photos.life-moments.local", sourceRoot, CoreWebView2HostResourceAccessKind.DenyCors);
            if (scriptId != null) web.CoreWebView2.RemoveScriptToExecuteOnDocumentCreated(scriptId);
            var preloadData = photoData; var preloadIndex = index; var preloadStatus = Status(); string preloadSession = session;
            string preload = await Task.Run(() => {
                var encoder = new JavaScriptSerializer { MaxJsonLength = 64 * 1024 * 1024 };
                return "window.desktopPhotoData=" + encoder.Serialize(preloadData) + ";window.desktopIndex=" + encoder.Serialize(preloadIndex) + ";window.desktopSession=" + encoder.Serialize(preloadSession) + ";window.desktopStatus=" + encoder.Serialize(preloadStatus) + ";window.desktopVersion='0.3.0-beta.9';";
            });
            if (closing || web.IsDisposed) return;
            scriptId = await web.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync(preload);
            if (closing || web.IsDisposed) return;
            web.CoreWebView2.Navigate(AppOrigin + "index.html");
        }
        finally { busy = false; }
    }
    void BeginMetadataScan()
    {
        if (closing || web.IsDisposed) return;
        scanCancel = new CancellationTokenSource(); int current = generation;
        var progress = new Progress<string>(async text => { if (current == generation && ready && !closing) await ExecuteAsync("window.desktopScanProgress", text); });
        scanTask = UpdateMetadataAsync(current, scanCancel.Token, progress);
    }
    async Task UpdateMetadataAsync(int current, CancellationToken token, IProgress<string> progress)
    {
        try
        {
            var updated = await Task.Run(() => library.Enrich(sourceRoot, photoData, token, progress));
            if (current != generation || token.IsCancellationRequested || closing) return;
            SetPhotoData(updated); await ExecuteAsync("window.applyDesktopPhotos", photoData); QueueSync();
        }
        catch (OperationCanceledException) { }
        catch (Exception e) { Log("Scan: " + e.Message); var ignored = ExecuteAsync("window.desktopScanProgress", "读取失败，点击刷新重试"); }
    }
    async Task RefreshAsync()
    {
        if (busy) return; busy = true; scanCancel.Cancel(); await scanTask;
        try { generation++; await ExecuteAsync("window.desktopScanProgress", "正在检查目录变化…"); SetPhotoData(await Task.Run(() => library.Discover(sourceRoot))); await ExecuteAsync("window.applyDesktopPhotos", photoData); BeginMetadataScan(); }
        finally { busy = false; }
    }
    async void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!e.Source.StartsWith(AppOrigin, StringComparison.OrdinalIgnoreCase)) return;
        try
        {
            var m = json.Deserialize<Dictionary<string, object>>(e.WebMessageAsJson);
            if (Convert.ToString(Get(m, "session", "")) != session) return;
            string type = Convert.ToString(Get(m, "type", ""));
            if (m.ContainsKey("store") && new[] { "save-index", "choose-folder", "refresh", "export-index", "sync-now", "toggle-sync" }.Contains(type)) { index = (Dictionary<string, object>)m["store"]; library.SaveIndex(sourceRoot, index); if (type == "save-index") QueueSync(); }
            if (type == "choose-folder" && !choosing && !busy)
            {
                choosing = true;
                try { using (var d = new FolderBrowserDialog { Description = "选择照片母目录（包含所有子目录）", SelectedPath = sourceRoot, ShowNewFolderButton = false }) { if (d.ShowDialog(this) == DialogResult.OK) await LoadFolderAsync(d.SelectedPath); } }
                finally { choosing = false; }
            }
            else if (type == "refresh") await RefreshAsync();
            else if (type == "export-index")
            {
                using (var d = new SaveFileDialog { Filter = "相册索引 (*.json)|*.json", FileName = "生活点滴-索引.json", AddExtension = true })
                { if (d.ShowDialog(this) == DialogResult.OK) { if (PhotoLibrary.IsWithin(d.FileName, sourceRoot)) throw new InvalidOperationException("请将索引导出到原照片目录以外。"); SaveAtomic(d.FileName, json.Serialize(Snapshot())); } }
            }
            else if (type == "configure-sync") { ConfigureCloud(); await SendStatusAsync(); }
            else if (type == "toggle-sync")
            {
                bool enable = Convert.ToBoolean(m["enabled"]); if (enable && !cloud.Connected) ConfigureCloud();
                settings["syncEnabled"] = enable && cloud.Connected; SaveSettings();
                if (!Enabled) { syncTimer.Stop(); syncCancel.Cancel(); } else { cloudState = "等待同步"; QueueSync(); }
                await SendStatusAsync();
            }
            else if (type == "sync-now") { if (!cloud.Connected) ConfigureCloud(); if (Enabled) await SynchronizeAsync(); else await SendStatusAsync(); }
        }
        catch (Exception ex) { Log(ex.Message); var ignored = ExecuteAsync("window.desktopNotice", ex.Message); }
    }
    async void OnThumbnail(object sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var defer = e.GetDeferral();
        try
        {
            var uri = new Uri(e.Request.Uri); bool full = uri.Host == "rendered.life-moments.local";
            string name = Path.GetFileNameWithoutExtension(uri.AbsolutePath);
            Dictionary<string, object> p;
            if (!photosById.TryGetValue(name, out p)) { e.Response = web.CoreWebView2.Environment.CreateWebResourceResponse(null, 404, "Not Found", ""); return; }
            string file = Convert.ToString(p["path"]), stamp = Convert.ToString(p["stamp"]);
            // The URL version must identify these bytes before they can be cached as immutable.
            if (Uri.UnescapeDataString(uri.Query.TrimStart('?')) != "v=" + stamp) { e.Response = web.CoreWebView2.Environment.CreateWebResourceResponse(null, 404, "Not Found", ""); return; }
            byte[] bytes = await ImageBytesAsync(file, stamp, full);
            if (!web.IsDisposed) e.Response = web.CoreWebView2.Environment.CreateWebResourceResponse(new MemoryStream(bytes), 200, "OK", "Content-Type: " + (full ? "image/png" : "image/jpeg") + "\r\nCache-Control: max-age=31536000, immutable\r\n");
        }
        catch { if (!web.IsDisposed && web.CoreWebView2 != null) e.Response = web.CoreWebView2.Environment.CreateWebResourceResponse(null, 404, "Not Found", ""); }
        finally { try { defer.Complete(); } catch (ObjectDisposedException) { } }
    }
    void SetPhotoData(Dictionary<string, object> value)
    {
        // Replace a complete snapshot on the UI thread; each image lookup is then O(1).
        var lookup = new Dictionary<string, Dictionary<string, object>>();
        foreach (var p in PhotoLibrary.Photos(value)) lookup[Convert.ToString(p["id"])] = p;
        photoData = value; photosById = lookup;
    }
    async Task<byte[]> ImageBytesAsync(string file, string stamp, bool full)
    {
        string key = PhotoLibrary.Canonical(file) + "/" + stamp + (full ? "/full" : "/thumbnail");
        var request = imageRequests.GetOrAdd(key, k => new Lazy<Task<byte[]>>(() => ReadImageAsync(file, stamp, full), LazyThreadSafetyMode.ExecutionAndPublication));
        try { return await request.Value; }
        finally
        {
            // A later request for this key must not be removed by another waiter on an older task.
            ((ICollection<KeyValuePair<string, Lazy<Task<byte[]>>>>)imageRequests).Remove(new KeyValuePair<string, Lazy<Task<byte[]>>>(key, request));
        }
    }
    async Task<byte[]> ReadImageAsync(string file, string stamp, bool full)
    {
        byte[] cached = await Task.Run(() => { byte[] bytes; return library.TryReadCachedImage(file, stamp, full, out bytes) ? bytes : null; });
        if (cached != null) return cached;
        // A full-size HEIF request has its own bounded queue and never waits behind gallery thumbnails.
        SemaphoreSlim decoder = full ? fullImages : thumbnails;
        await decoder.WaitAsync();
        try { return await Task.Run(() => full ? library.RenderHeif(file, stamp, false) : library.Thumbnail(file, stamp)); }
        finally { decoder.Release(); }
    }
    object Snapshot()
    {
        string cloudRoot = Convert.ToString(Get(index, "cloudRoot", "/生活点滴"));
        var photos = PhotoLibrary.Photos(photoData).Select(p => p.Where(kv => !new[] { "path", "image", "fullImage", "stamp" }.Contains(kv.Key)).ToDictionary(kv => kv.Key, kv => kv.Value)).ToList();
        foreach (var p in photos) p["cloudPath"] = cloudRoot.TrimEnd('/') + "/" + p["relativePath"];
        var exportedIndex = index.Where(kv => !new[] { "cloudRoots", "migratedIndexes" }.Contains(kv.Key)).ToDictionary(kv => kv.Key, kv => kv.Value);
        return new { schemaVersion = 2, createdAt = DateTime.UtcNow.ToString("o"), libraryId = PhotoLibrary.Hash(PhotoLibrary.Canonical(sourceRoot)).ToString("x"), cloudRoot = cloudRoot, photos = photos, index = exportedIndex };
    }
    new bool Enabled { get { return Convert.ToBoolean(Get(settings, "syncEnabled", false)); } }
    object Status() { return new { syncEnabled = Enabled, connected = cloud != null && cloud.Connected, syncBusy = syncBusy, syncText = cloudState.Length > 0 ? cloudState : Enabled ? "等待同步" : "同步已关闭", lastSync = cloud == null ? "" : cloud.LastSync, cloudFolder = cloud == null ? "" : cloud.Folder }; }
    Task SendStatusAsync() { return ExecuteAsync("window.setDesktopStatus", Status()); }
    async Task ExecuteAsync(string fn, object v)
    {
        if (!ready || closing || web.IsDisposed) return;
        int current = generation;
        string argument = fn == "window.applyDesktopPhotos" ? await Task.Run(() => new JavaScriptSerializer { MaxJsonLength = 64 * 1024 * 1024 }.Serialize(v)) : json.Serialize(v);
        if (!ready || closing || web.IsDisposed || current != generation) return;
        await web.CoreWebView2.ExecuteScriptAsync("if(" + fn + ")" + fn + "(" + argument + ")");
    }
    void QueueSync() { syncRevision++; if (Enabled && cloud.Connected && !selfTest && !closing) { syncTimer.Stop(); syncTimer.Start(); } }
    async Task SynchronizeAsync()
    {
        if (!Enabled || !cloud.Connected || syncBusy || closing) return;
        int revision = syncRevision; syncBusy = true; syncCancel = new CancellationTokenSource(); cloudState = "正在同步索引…"; await SendStatusAsync();
        try { bool changed = await cloud.UploadAsync(Encoding.UTF8.GetBytes(json.Serialize(Snapshot())), syncCancel.Token); cloudState = changed ? "已同步 · " + DateTime.Now.ToString("HH:mm") : "已是最新"; }
        catch (OperationCanceledException) { cloudState = "同步已关闭"; }
        catch (Exception e) { cloudState = "同步失败，可重试"; Log("Sync: " + e.Message); var ignored = ExecuteAsync("window.desktopNotice", e.Message); }
        finally { syncBusy = false; if (!Enabled) cloudState = "同步已关闭"; }
        await SendStatusAsync();
        if (revision != syncRevision) QueueSync();
    }
    void ConfigureCloud() { using (var d = new CloudSettingsDialog(cloud)) d.ShowDialog(this); }
    void SaveSettings() { SaveAtomic(Path.Combine(dataRoot, "settings.json"), json.Serialize(settings)); }
    internal static object Get(Dictionary<string, object> o, string k, object fallback) { object v; return o != null && o.TryGetValue(k, out v) ? v : fallback; }
    internal static void SaveAtomic(string path, string value)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))); string temp = path + ".tmp"; File.WriteAllText(temp, value, new UTF8Encoding(false));
        if (File.Exists(path)) File.Replace(temp, path, path + ".bak", true); else File.Move(temp, path);
    }
    static void OpenExternal(string value) { Uri uri; if (Uri.TryCreate(value, UriKind.Absolute, out uri) && new[] { "https", "http" }.Contains(uri.Scheme)) Process.Start(new ProcessStartInfo(value) { UseShellExecute = true }); }
    void Log(string value) { File.AppendAllText(Path.Combine(dataRoot, "app.log"), DateTime.Now.ToString("o") + " " + value + Environment.NewLine); }
}
