using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

// No real accounts, browser launches or wall-clock waits. Credentials stay in the test root.
internal static class BaiduAuthorizationTests
{
    static void Require(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
    internal static async Task RunAsync(string root)
    {
        Directory.CreateDirectory(root);
        string successRoot = Path.Combine(root, "success"); Directory.CreateDirectory(successRoot);
        var waits = new List<int>(); string displayed = "", opened = "";
        var handler = new AuthHandler("authorization_pending", "slow_down", "success");
        using (var sync = new BaiduSync(successRoot, handler, url => opened = url, (milliseconds, token) => { token.ThrowIfCancellationRequested(); waits.Add(milliseconds); return Task.FromResult(0); }))
        {
            await sync.AuthorizeAsync("test-key", "test-secret", "/apps/test/index", text => displayed = text, CancellationToken.None);
            Require(sync.Connected && handler.Polls == 3, "HTTP 400 polling did not reach successful authorization");
            Require(waits.Count == 3 && waits[0] == 3000 && waits[1] == 3000 && waits[2] == 8000, "slow_down did not increase polling interval");
            Require(opened == "https://openapi.baidu.com/device" && displayed.Contains("TEST-CODE"), "User authorization instructions missing");
        }
        using (var restored = new BaiduSync(successRoot)) Require(restored.Connected && restored.AppKey == "test-key", "Successful authorization was not persisted");

        foreach (string error in new[] { "invalid_client", "unauthorized_client", "access_denied", "expired_token", "unknown_oauth_error", "malformed", "server", "false-success", "empty-token" })
        {
            string caseRoot = Path.Combine(root, error); Directory.CreateDirectory(caseRoot);
            var failedHandler = new AuthHandler(error); bool rejected = false;
            using (var sync = new BaiduSync(caseRoot, failedHandler, url => { }, (milliseconds, token) => Task.FromResult(0)))
            {
                try { await sync.AuthorizeAsync("test-key", "test-secret", "/apps/test/index", text => { }, CancellationToken.None); }
                catch (InvalidOperationException e)
                {
                    rejected = true;
                    if (error == "malformed" || error == "false-success") Require(e.Message.Contains("HTTP 400"), "Unstructured HTTP 400 was accepted");
                    else if (error == "server") Require(e.Message.Contains("HTTP 503"), "Server error was treated as pending authorization");
                    else if (error == "empty-token") Require(e.Message.Contains("访问令牌"), "Empty token was accepted");
                    else Require(e.Message.Contains(error), "OAuth diagnostic was hidden by HTTP status");
                    Require(!e.Message.Contains("PRIVATE-DESCRIPTION"), "Untrusted response body leaked into diagnostics");
                }
                Require(rejected && !sync.Connected && failedHandler.Polls == 1, "Terminal error continued polling or connected");
                using (var restored = new BaiduSync(caseRoot))
                {
                    Require(!restored.Connected && restored.InputAppKey == "test-key" && restored.InputSecret == "test-secret" && restored.InputFolder == "/apps/test/index", "Failed authorization lost inputs or saved an authorized connection");
                }
            }
        }
        string draftRoot = Path.Combine(root, "draft"); Directory.CreateDirectory(draftRoot);
        using (var draft = new BaiduSync(draftRoot))
        {
            draft.SetTestCredentials(); draft.SaveDraft("changed-key", "changed-secret", "invalid draft path");
            Require(draft.Connected && draft.AppKey == "test-key" && draft.Secret == "test-secret", "Draft edits changed the active account");
        }
        using (var draft = new BaiduSync(draftRoot))
        {
            Require(draft.Connected && draft.InputAppKey == "changed-key" && draft.InputSecret == "changed-secret" && draft.InputFolder == "invalid draft path", "Window close/reopen did not preserve drafts");
        }
        string encryptedDraft = System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(draftRoot, "baidu-credentials.bin")));
        Require(!encryptedDraft.Contains("changed-secret") && !encryptedDraft.Contains("changed-key"), "Draft credentials saved as plaintext");

        var cancelled = new CancellationTokenSource(); var cancelHandler = new AuthHandler("success");
        string cancelRoot = Path.Combine(root, "cancel"); Directory.CreateDirectory(cancelRoot);
        using (var sync = new BaiduSync(cancelRoot, cancelHandler, url => { }, (milliseconds, token) => { cancelled.Cancel(); return Task.FromResult(0); }))
        {
            bool stopped = false;
            try { await sync.AuthorizeAsync("test-key", "test-secret", "/apps/test/index", text => { }, cancelled.Token); }
            catch (OperationCanceledException) { stopped = true; }
            Require(stopped && cancelHandler.Polls == 0 && !sync.Connected, "Cancelled authorization fetched or stored tokens");
        }
        var timeoutHandler = new AuthHandler("success") { ExpiresIn = 0 };
        using (var sync = new BaiduSync(cancelRoot, timeoutHandler, url => { }, (milliseconds, token) => Task.FromResult(0)))
        {
            bool timedOut = false;
            try { await sync.AuthorizeAsync("test-key", "test-secret", "/apps/test/index", text => { }, CancellationToken.None); }
            catch (InvalidOperationException e) { timedOut = e.Message.Contains("超时"); }
            Require(timedOut && timeoutHandler.Polls == 0 && !sync.Connected, "Expired device code was accepted");
        }
        File.WriteAllText(Path.Combine(root, "PASS.txt"), "PASS: HTTP400 pending; slow_down; success/persistence; terminal errors; malformed HTTP400; HTTP503; no token on HTTP400; empty token; cancellation; deadline; encrypted drafts retained across failures/reopen; active connection preserved.");
    }

    sealed class AuthHandler : HttpMessageHandler
    {
        readonly Queue<string> replies;
        internal int Polls, ExpiresIn = 600;
        internal AuthHandler(params string[] replies) { this.replies = new Queue<string>(replies); }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested(); string body; HttpStatusCode status = HttpStatusCode.OK;
            if (request.RequestUri.AbsolutePath.EndsWith("/device/code"))
                body = "{\"device_code\":\"TEST-DEVICE\",\"user_code\":\"TEST-CODE\",\"verification_url\":\"https://openapi.baidu.com/device\",\"interval\":3,\"expires_in\":" + ExpiresIn + "}";
            else
            {
                Require(request.RequestUri.Query.Contains("grant_type=device_token") && request.RequestUri.Query.Contains("code=TEST-DEVICE"), "Wrong device-token request");
                Polls++; Require(replies.Count > 0, "Unexpected extra token poll"); string reply = replies.Dequeue();
                body = "{\"access_token\":\"TEST-TOKEN\",\"refresh_token\":\"TEST-REFRESH\",\"expires_in\":3600}";
                if (reply == "empty-token") body = "{\"access_token\":\"\"}";
                else if (reply != "success")
                {
                    status = reply == "server" ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.BadRequest;
                    if (reply == "malformed") body = "<html>PRIVATE-DESCRIPTION</html>";
                    else if (reply != "false-success") body = "{\"error\":\"" + (reply == "server" ? "authorization_pending" : reply) + "\",\"error_description\":\"PRIVATE-DESCRIPTION\"}";
                }
            }
            return Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(body) });
        }
    }
}
