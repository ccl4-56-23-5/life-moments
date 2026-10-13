using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

internal sealed class PhotoLibrary
{
    readonly string root, cachePath, indexPath;
    readonly JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = 64 * 1024 * 1024 };
    Dictionary<string, object> cache = new Dictionary<string, object>();
    internal PhotoLibrary(string dataRoot)
    {
        root = dataRoot; cachePath = Path.Combine(root, "photo-cache-v2.json"); indexPath = Path.Combine(root, "library-index-v2.json");
        if (File.Exists(cachePath)) { try { cache = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(cachePath)); } catch { File.Copy(cachePath, cachePath + ".unreadable-" + DateTime.Now.ToString("yyyyMMddHHmmss")); } }
    }
    internal static string Canonical(string path) { return Path.GetFullPath(path).TrimEnd('\\', '/').Replace('\\', '/').ToLowerInvariant(); }
    internal static bool IsWithin(string path, string folder) { string p = Canonical(path), f = Canonical(folder); return p == f || p.StartsWith(f + "/", StringComparison.Ordinal); }
    internal static long Hash(string s) { using (var sha = SHA256.Create()) return BitConverter.ToInt64(sha.ComputeHash(Encoding.UTF8.GetBytes(s)), 0) & 0x0000FFFFFFFFFFFFL; }
    internal static IEnumerable<Dictionary<string, object>> Photos(Dictionary<string, object> data) { return ((IEnumerable)data["photos"]).Cast<Dictionary<string, object>>(); }
    internal static IEnumerable<string> Files(string folder, List<string> errors)
    {
        var stack = new Stack<string>(); stack.Push(folder);
        while (stack.Count > 0)
        {
            string next = stack.Pop(); string[] files, dirs;
            try { files = Directory.GetFiles(next); dirs = Directory.GetDirectories(next); }
            catch (Exception e) { if (!(e is IOException) && !(e is UnauthorizedAccessException)) throw; errors.Add(next + ": " + e.Message); continue; }
            foreach (string f in files) if ((File.GetAttributes(f) & FileAttributes.ReparsePoint) == 0) yield return f;
            foreach (string d in dirs) if ((File.GetAttributes(d) & FileAttributes.ReparsePoint) == 0) stack.Push(d);
        }
    }
    internal Dictionary<string, object> Discover(string folder)
    {
        var sw = Stopwatch.StartNew(); folder = Path.GetFullPath(folder).TrimEnd('\\'); var photos = new List<Dictionary<string, object>>(); var errors = new List<string>();
        int videos = 0, unsupported = 0, pending = 0;
        foreach (string file in Files(folder, errors))
        {
            string ext = Path.GetExtension(file).ToLowerInvariant(); if (new[] { ".mp4", ".mov", ".mkv" }.Contains(ext)) { videos++; continue; }
            if (!new[] { ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".heic", ".heif" }.Contains(ext)) continue;
            try
            {
                var fi = new FileInfo(file); string key = Canonical(file), stamp = fi.Length + "-" + fi.LastWriteTimeUtc.Ticks;
                object saved; Dictionary<string, object> p;
                if (cache.TryGetValue(key, out saved) && Convert.ToString(((Dictionary<string, object>)saved)["stamp"]) == stamp) p = new Dictionary<string, object>((Dictionary<string, object>)saved);
                else
                {
                    DateTime date = fi.LastWriteTime; var match = Regex.Match(fi.Name, @"(?<!\d)(20\d{2})[-_]?(\d{2})[-_]?(\d{2})"); DateTime parsed;
                    if (match.Success && DateTime.TryParseExact(match.Groups[1].Value + match.Groups[2].Value + match.Groups[3].Value, "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out parsed)) date = parsed;
                    p = new Dictionary<string, object> { { "id", Hash(key) }, { "path", file }, { "stamp", stamp }, { "name", fi.Name }, { "size", fi.Length }, { "date", date.ToString("yyyy-MM-dd") }, { "month", date.ToString("yyyy-MM") }, { "time", date.ToString("yyyy:MM:dd HH:mm:ss") }, { "dateSource", "文件名 / 修改时间" }, { "width", 0 }, { "height", 0 }, { "gps", null }, { "gpsMethod", "" }, { "gpsDatum", "" }, { "camera", "" }, { "metadataReady", false } };
                    pending++;
                }
                string relative = file.Substring(folder.Length).TrimStart('\\').Replace('\\', '/');
                p["relativePath"] = relative; p["image"] = "https://thumbs.life-moments.local/" + p["id"] + ".jpg?v=" + stamp;
                p["format"] = ext.TrimStart('.').ToUpperInvariant();
                p["fullImage"] = HeifDecoder.Supports(file) ? "https://rendered.life-moments.local/" + p["id"] + ".png?v=" + stamp : "https://photos.life-moments.local/" + string.Join("/", relative.Split('/').Select(Uri.EscapeDataString)); photos.Add(p);
            }
            catch (IOException e) { errors.Add(Path.GetFileName(file) + ": " + e.Message); }
        }
        photos = photos.OrderByDescending(p => Convert.ToString(p["time"])).ThenBy(p => Convert.ToString(p["name"])).ToList();
        return new Dictionary<string, object> { { "source", folder }, { "label", new DirectoryInfo(folder).Name }, { "photos", photos }, { "count", photos.Count }, { "samples", photos.Count }, { "videoCount", videos }, { "unsupportedCount", unsupported }, { "errors", errors }, { "pending", pending }, { "discoverMs", sw.ElapsedMilliseconds }, { "scanComplete", pending == 0 } };
    }
    internal Dictionary<string, object> Enrich(string folder, Dictionary<string, object> data, CancellationToken token, IProgress<string> progress)
    {
        var sw = Stopwatch.StartNew(); var ps = Photos(data).Select(p => new Dictionary<string, object>(p)).ToList(); int reads = 0, processed = 0;
        try
        {
            foreach (var p in ps)
            {
                token.ThrowIfCancellationRequested(); processed++;
                if (Convert.ToBoolean(p["metadataReady"])) continue;
                ReadMetadata(p); reads++; cache[Canonical(Convert.ToString(p["path"]))] = new Dictionary<string, object>(p);
                if (reads % 25 == 0) { progress.Report("正在整理拍摄时间与位置 · " + processed + " / " + ps.Count); if (reads % 250 == 0) SaveCache(); }
            }
        }
        finally { if (reads > 0) SaveCache(); }
        var updated = new Dictionary<string, object>(data); updated["photos"] = ps.OrderByDescending(p => Convert.ToString(p["time"])).ThenBy(p => Convert.ToString(p["name"])).ToList();
        updated["scanComplete"] = true; updated["pending"] = 0; updated["metadataReads"] = reads; updated["scanMs"] = sw.ElapsedMilliseconds;
        updated["errors"] = ((IEnumerable)data["errors"]).Cast<object>().Concat(ps.Where(p => p.ContainsKey("readError")).Select(p => p["name"] + ": " + p["readError"])).ToList(); return updated;
    }
    void SaveCache() { PhotoWindow.SaveAtomic(cachePath, json.Serialize(cache)); }
    static void ReadMetadata(Dictionary<string, object> p)
    {
        try
        {
            string ext = Path.GetExtension(Convert.ToString(p["path"])).ToLowerInvariant();
            if (ext == ".jpg" || ext == ".jpeg" || HeifDecoder.Supports(Convert.ToString(p["path"])))
            {
                var meta = HeifDecoder.Supports(Convert.ToString(p["path"])) ? HeifDecoder.Metadata(Convert.ToString(p["path"])) : JpegMetadata.Read(Convert.ToString(p["path"])); p["width"] = meta.Orientation >= 5 ? meta.Height : meta.Width; p["height"] = meta.Orientation >= 5 ? meta.Width : meta.Height;
                p["camera"] = meta.Camera; p["gps"] = meta.Gps; p["gpsDatum"] = meta.Datum.Length <= 40 ? meta.Datum : "";
                p["gpsMethod"] = meta.Method.Split(' ').All(t => new[] { "GPS", "WLAN", "CELLID", "MANUAL" }.Contains(t)) ? meta.Method : "";
                DateTime date; if (DateTime.TryParseExact(meta.Date, "yyyy:MM:dd HH:mm:ss", CultureInfo.InvariantCulture, DateTimeStyles.None, out date)) { p["date"] = date.ToString("yyyy-MM-dd"); p["month"] = date.ToString("yyyy-MM"); p["time"] = date.ToString("yyyy:MM:dd HH:mm:ss"); p["dateSource"] = "EXIF"; }
                p["metadataReady"] = true; return;
            }
            using (Image im = Image.FromFile(Convert.ToString(p["path"])))
            {
                p["width"] = im.Width; p["height"] = im.Height; byte[] orient = Tag(im, 0x112);
                if (orient != null && BitConverter.ToUInt16(orient, 0) >= 5) { p["width"] = im.Height; p["height"] = im.Width; }
                p["camera"] = TextTag(im, 0x110); DateTime date;
                if (DateTime.TryParseExact(TextTag(im, 0x9003), "yyyy:MM:dd HH:mm:ss", CultureInfo.InvariantCulture, DateTimeStyles.None, out date)) { p["date"] = date.ToString("yyyy-MM-dd"); p["month"] = date.ToString("yyyy-MM"); p["time"] = date.ToString("yyyy:MM:dd HH:mm:ss"); p["dateSource"] = "EXIF"; }
                byte[] lat = Tag(im, 2), lng = Tag(im, 4);
                if (lat != null && lng != null && lat.Length >= 24 && lng.Length >= 24) { double a = Coord(lat), b = Coord(lng); if (TextTag(im, 1) == "S") a *= -1; if (TextTag(im, 3) == "W") b *= -1; if (Math.Abs(a) <= 90 && Math.Abs(b) <= 180 && !double.IsNaN(a) && !double.IsNaN(b)) p["gps"] = new[] { b, a }; }
                byte[] method = Tag(im, 27); if (method != null && method.Length > 8 && Encoding.ASCII.GetString(method, 0, 8) == "ASCII\0\0\0") { string text = Encoding.ASCII.GetString(method, 8, method.Length - 8).Split('\0')[0].Trim(); if (text.Split(' ').All(t => new[] { "GPS", "WLAN", "CELLID", "MANUAL" }.Contains(t))) p["gpsMethod"] = text; }
                string datum = TextTag(im, 18); p["gpsDatum"] = datum.Length <= 40 ? datum : "";
            }
        }
        catch (Exception e) { p["readError"] = e.Message; }
        p["metadataReady"] = true;
    }
    static byte[] Tag(Image im, int id) { try { return im.GetPropertyItem(id).Value; } catch (ArgumentException) { return null; } }
    static string TextTag(Image im, int id) { byte[] v = Tag(im, id); return v == null ? "" : Encoding.UTF8.GetString(v).TrimEnd('\0').Trim(); }
    static double Rational(byte[] v, int o) { uint d = BitConverter.ToUInt32(v, o + 4); return d == 0 ? double.NaN : (double)BitConverter.ToUInt32(v, o) / d; }
    static double Coord(byte[] v) { return Rational(v, 0) + Rational(v, 8) / 60 + Rational(v, 16) / 3600; }
    internal byte[] Thumbnail(string file, string stamp)
    {
        if (HeifDecoder.Supports(file)) return RenderHeif(file, stamp, true);
        string path = ImageCachePath(file, stamp, false);
        if (File.Exists(path)) return File.ReadAllBytes(path);
        using (var original = Image.FromFile(file))
        {
            // Use embedded previews only when they preserve the original framing and resolution.
            byte[] embedded = Tag(original, 0x501B); Image im = original; MemoryStream input = null;
            try
            {
                if (embedded != null && embedded.Length > 0)
                {
                    input = new MemoryStream(embedded); try { im = Image.FromStream(input); } catch { im = original; }
                    if (im != original)
                    {
                        double originalRatio = (double)original.Width / original.Height, embeddedRatio = (double)im.Width / im.Height;
                        bool cropped = Math.Abs(embeddedRatio / originalRatio - 1) > .01;
                        bool tooSmall = Math.Max(im.Width, im.Height) < 480 && Math.Max(original.Width, original.Height) > 480;
                        if (cropped || tooSmall) { im.Dispose(); im = original; }
                    }
                }
                byte[] orient = Tag(original, 0x112); if (orient != null) { int o = BitConverter.ToUInt16(orient, 0); RotateFlipType[] rotations = { RotateFlipType.RotateNoneFlipNone, RotateFlipType.RotateNoneFlipNone, RotateFlipType.RotateNoneFlipX, RotateFlipType.Rotate180FlipNone, RotateFlipType.Rotate180FlipX, RotateFlipType.Rotate90FlipX, RotateFlipType.Rotate90FlipNone, RotateFlipType.Rotate270FlipX, RotateFlipType.Rotate270FlipNone }; if (o >= 1 && o <= 8) im.RotateFlip(rotations[o]); }
                double scale = Math.Min(1, 480.0 / Math.Max(im.Width, im.Height));
                using (var thumb = new Bitmap(Math.Max(1, (int)(im.Width * scale)), Math.Max(1, (int)(im.Height * scale))))
                using (var g = Graphics.FromImage(thumb)) using (var output = new MemoryStream())
                {
                    g.CompositingQuality = CompositingQuality.HighSpeed; g.InterpolationMode = InterpolationMode.HighQualityBilinear; g.DrawImage(im, 0, 0, thumb.Width, thumb.Height); thumb.Save(output, ImageFormat.Jpeg);
                    byte[] bytes = output.ToArray(); Directory.CreateDirectory(Path.GetDirectoryName(path));
                    string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp"; File.WriteAllBytes(temp, bytes);
                    try { if (File.Exists(path)) File.Delete(temp); else File.Move(temp, path); } catch (IOException) { if (File.Exists(temp)) File.Delete(temp); if (!File.Exists(path)) throw; }
                    return bytes;
                }
            }
            finally { if (im != original) im.Dispose(); if (input != null) input.Dispose(); }
        }
    }
    internal byte[] RenderHeif(string file, string stamp, bool thumbnail)
    {
        string path = ImageCachePath(file, stamp, !thumbnail);
        if (File.Exists(path)) return File.ReadAllBytes(path);
        byte[] bytes = HeifDecoder.Render(file, thumbnail); Directory.CreateDirectory(Path.GetDirectoryName(path));
        string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp"; File.WriteAllBytes(temp, bytes);
        try { if (File.Exists(path)) File.Delete(temp); else File.Move(temp, path); } catch (IOException) { if (File.Exists(temp)) File.Delete(temp); if (!File.Exists(path)) throw; }
        return bytes;
    }
    string ImageCachePath(string file, string stamp, bool full)
    {
        string version = HeifDecoder.Supports(file) ? "/heif-v1" : "/thumb-v2";
        return Path.Combine(root, full ? "rendered" : "thumbnails", Hash(Canonical(file) + "/" + stamp + version).ToString("x") + (full ? ".png" : ".jpg"));
    }
    internal bool TryReadCachedImage(string file, string stamp, bool full, out byte[] bytes)
    {
        string path = ImageCachePath(file, stamp, full); bytes = null;
        if (!File.Exists(path)) return false; bytes = File.ReadAllBytes(path); return true;
    }
    internal Dictionary<string, object> LoadIndex(string folder)
    {
        var index = File.Exists(indexPath) ? json.Deserialize<Dictionary<string, object>>(File.ReadAllText(indexPath)) : new Dictionary<string, object> { { "people", new object[0] }, { "assignments", new Dictionary<string, object>() }, { "notes", new Dictionary<string, object>() }, { "profiles", new Dictionary<string, object>() }, { "photoLocations", new Dictionary<string, object>() }, { "albums", new object[0] }, { "gpsCrs", "WGS84" } };
        if (!index.ContainsKey("cloudRoots")) index["cloudRoots"] = new Dictionary<string, object>();
        var mappings = (Dictionary<string, object>)index["cloudRoots"];
        var migrated = ((IEnumerable)PhotoWindow.Get(index, "migratedIndexes", new object[0])).Cast<object>().Select(Convert.ToString).ToList();
        var candidates = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { folder };
        var parent = Directory.GetParent(folder); if (parent != null) candidates.Add(parent.FullName);
        foreach (string c in Directory.GetDirectories(folder)) candidates.Add(c);
        foreach (string oldRoot in candidates.Where(Directory.Exists))
        {
            string legacyRoot = Path.GetFullPath(oldRoot).TrimEnd('\\'), key = Hash(legacyRoot.ToLowerInvariant()).ToString("x"); string path = Path.Combine(root, "indexes", key + ".json");
            if (!File.Exists(path) || migrated.Contains(key)) continue;
            var old = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(path)); var ids = new Dictionary<string, long>();
            foreach (string file in Files(legacyRoot, new List<string>())) { string relative = file.Substring(legacyRoot.Length).TrimStart('\\').Replace('\\', '/'); ids[Hash(legacyRoot.ToLowerInvariant() + "/" + relative.ToLowerInvariant()).ToString()] = Hash(Canonical(file)); }
            foreach (string field in new[] { "assignments", "notes", "photoLocations" })
            {
                var into = (Dictionary<string, object>)index[field]; var from = (Dictionary<string, object>)PhotoWindow.Get(old, field, new Dictionary<string, object>());
                foreach (var kv in from) { long id; string target = ids.TryGetValue(kv.Key, out id) ? id.ToString() : kv.Key; if (!into.ContainsKey(target)) into[target] = kv.Value; }
            }
            index["people"] = ((IEnumerable)index["people"]).Cast<object>().Concat(((IEnumerable)old["people"]).Cast<object>()).Distinct().ToArray();
            var profiles = (Dictionary<string, object>)index["profiles"];
            foreach (var kv in (Dictionary<string, object>)PhotoWindow.Get(old, "profiles", new Dictionary<string, object>()))
            {
                var p = (Dictionary<string, object>)kv.Value; if (p.ContainsKey("avatar")) { var a = (Dictionary<string, object>)p["avatar"]; long id; if (ids.TryGetValue(Convert.ToString(a["photoId"]), out id)) a["photoId"] = id; }
                if (!profiles.ContainsKey(kv.Key)) profiles[kv.Key] = p;
            }
            var albums = ((IEnumerable)index["albums"]).Cast<Dictionary<string, object>>().ToList();
            foreach (var a in ((IEnumerable)old["albums"]).Cast<Dictionary<string, object>>())
            {
                a["ids"] = ((IEnumerable)a["ids"]).Cast<object>().Select(v => { long id; return ids.TryGetValue(Convert.ToString(v), out id) ? id : Convert.ToInt64(v); }).Distinct().ToArray();
                var target = albums.FirstOrDefault(x => Convert.ToString(x["name"]) == Convert.ToString(a["name"]));
                if (target == null) albums.Add(a); else target["ids"] = ((IEnumerable)target["ids"]).Cast<object>().Concat(((IEnumerable)a["ids"]).Cast<object>()).Select(Convert.ToInt64).Distinct().ToArray();
            }
            index["albums"] = albums; if (old.ContainsKey("gpsCrs")) index["gpsCrs"] = old["gpsCrs"];
            mappings[Canonical(legacyRoot)] = PhotoWindow.Get(old, "cloudRoot", "/" + new DirectoryInfo(legacyRoot).Name); migrated.Add(key);
        }
        index["migratedIndexes"] = migrated.ToArray(); index["schemaVersion"] = 2;
        index["cloudRoot"] = PhotoWindow.Get(mappings, Canonical(folder), "/" + new DirectoryInfo(folder).Name);
        SaveIndex(folder, index); return index;
    }
    internal void SaveIndex(string folder, Dictionary<string, object> index)
    {
        if (!index.ContainsKey("cloudRoots")) index["cloudRoots"] = new Dictionary<string, object>();
        ((Dictionary<string, object>)index["cloudRoots"])[Canonical(folder)] = index["cloudRoot"];
        PhotoWindow.SaveAtomic(indexPath, json.Serialize(index));
    }
}
