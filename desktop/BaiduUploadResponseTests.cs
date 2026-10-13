using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

internal static class BaiduUploadResponseTests
{
    static void Require(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
    internal static async Task RunAsync(string root)
    {
        Directory.CreateDirectory(root);
        byte[] snapshot = Encoding.UTF8.GetBytes("{\"schemaVersion\":2,\"createdAt\":\"test\",\"index\":{}}");
        var handler = new UploadHandler();
        using (var sync = new BaiduSync(root, handler))
        {
            sync.SetTestCredentials();
            Require(await sync.UploadAsync(snapshot, CancellationToken.None), "UTF8 charset response prevented publication");
            Require(handler.Parts == 1 && handler.Finalized == 1 && sync.LastSync.Length > 0, "Upload did not finalize after charset response");
            Require(!await sync.UploadAsync(snapshot, CancellationToken.None) && handler.Finalized == 1, "Unchanged snapshot was republished");
        }
        foreach (string fault in new[] { "bad-md5", "html", "http400" })
        {
            var failed = new UploadHandler { Fault = fault };
            using (var sync = new BaiduSync(root, failed))
            {
                sync.SetTestCredentials(); bool rejected = false;
                try { await sync.UploadAsync(snapshot, CancellationToken.None); }
                catch (InvalidOperationException e)
                {
                    rejected = fault == "bad-md5" ? e.Message.Contains("校验失败") : fault == "http400" ? e.Message.Contains("HTTP 400") : e.Message.Contains("响应格式无效");
                }
                Require(rejected && failed.Finalized == 0 && sync.LastSync.Length == 0, "Invalid upload response reported publication success: " + fault);
            }
        }
        File.WriteAllText(Path.Combine(root, "PASS.txt"), "PASS: PCS text/html charset=utf8 JSON with UTF8 BOM; publication; deduplication; MD5 mismatch; real HTML; non-OAuth HTTP400 rejected.");
    }
    sealed class UploadHandler : HttpMessageHandler
    {
        internal int Parts, Finalized; internal string Fault;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested(); bool part = request.RequestUri.AbsolutePath.EndsWith("superfile2");
            string reply; HttpStatusCode status = HttpStatusCode.OK;
            if (part)
            {
                Parts++; var multipart = (MultipartFormDataContent)request.Content; byte[] bytes = await multipart.First().ReadAsByteArrayAsync();
                reply = "{\"md5\":\"" + (Fault == "bad-md5" ? "wrong" : BaiduSync.Md5(bytes)) + "\",\"request_id\":123}";
                if (Fault == "html") reply = "<html>gateway unavailable</html>";
                if (Fault == "http400") { status = HttpStatusCode.BadRequest; reply = "{\"error\":\"authorization_pending\"}"; }
            }
            else if (request.RequestUri.Query.Contains("method=precreate")) reply = "{\"errno\":0,\"return_type\":1,\"uploadid\":\"TEST-ID\",\"block_list\":[0]}";
            else if ((await request.Content.ReadAsStringAsync()).Contains("isdir=0")) { Finalized++; reply = "{\"errno\":0,\"fs_id\":123}"; }
            else reply = "{\"errno\":-8}";
            // Real PCS responses include a UTF-8 BOM and the Framework-incompatible alias.
            var content = new ByteArrayContent(Encoding.UTF8.GetPreamble().Concat(Encoding.UTF8.GetBytes(reply)).ToArray());
            content.Headers.ContentType = new MediaTypeHeaderValue(part ? "text/html" : "application/json") { CharSet = part ? "utf8" : "UTF-8" };
            return new HttpResponseMessage(status) { Content = content };
        }
    }
}
