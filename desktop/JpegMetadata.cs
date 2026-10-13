using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

// Parse JPEG headers and TIFF EXIF without decoding or reading the image's compressed pixels.
internal sealed class JpegMetadata
{
    internal int Width, Height, Orientation = 1;
    internal string Date = "", Camera = "", Method = "", Datum = "";
    internal double[] Gps;
    internal byte[] Thumbnail;
    byte[] tiff; bool little;
    internal static JpegMetadata FromExif(byte[] bytes)
    {
        var result = new JpegMetadata(); if (bytes == null || bytes.Length < 8) return result;
        byte[] data = bytes;
        if (Encoding.ASCII.GetString(bytes, 0, 6) != "Exif\0\0") { data = new byte[bytes.Length + 6]; Buffer.BlockCopy(Encoding.ASCII.GetBytes("Exif\0\0"), 0, data, 0, 6); Buffer.BlockCopy(bytes, 0, data, 6, bytes.Length); }
        result.ParseExif(data); return result;
    }
    internal static JpegMetadata Read(string path)
    {
        var result = new JpegMetadata();
        using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096))
        {
            if (stream.ReadByte() != 255 || stream.ReadByte() != 216) throw new InvalidDataException("JPEG 文件头无效。");
            while (stream.Position < stream.Length)
            {
                int b; do { b = stream.ReadByte(); } while (b >= 0 && b != 255); if (b < 0) break;
                int marker; do { marker = stream.ReadByte(); } while (marker == 255);
                if (marker < 0 || marker == 0xDA || marker == 0xD9) break;
                if (marker == 0 || marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) continue;
                int hi = stream.ReadByte(), lo = stream.ReadByte(); if (hi < 0 || lo < 0) throw new InvalidDataException("JPEG 文件头不完整。");
                int length = (hi << 8 | lo) - 2; if (length < 0 || stream.Position + length > stream.Length) throw new InvalidDataException("JPEG 数据段长度无效。");
                long end = stream.Position + length;
                if (marker == 0xE1)
                {
                    var data = new byte[length]; int offset = 0; while (offset < length) { int n = stream.Read(data, offset, length - offset); if (n == 0) throw new EndOfStreamException(); offset += n; }
                    if (length >= 14 && Encoding.ASCII.GetString(data, 0, 6) == "Exif\0\0") { try { result.ParseExif(data); } catch (InvalidDataException) { } }
                }
                else if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && length >= 5)
                { stream.ReadByte(); result.Height = stream.ReadByte() << 8 | stream.ReadByte(); result.Width = stream.ReadByte() << 8 | stream.ReadByte(); }
                stream.Position = end;
            }
        }
        return result;
    }
    void ParseExif(byte[] data)
    {
        tiff = new byte[data.Length - 6]; Buffer.BlockCopy(data, 6, tiff, 0, tiff.Length);
        little = tiff[0] == 73 && tiff[1] == 73; if (!little && !(tiff[0] == 77 && tiff[1] == 77)) return;
        if (U16(2) != 42) return; int first = Offset(U32(4)); var main = Ifd(first);
        Camera = Text(main, 0x110); Orientation = (int)Number(main, 0x112, 1);
        var exif = Ifd(Offset(Number(main, 0x8769, 0))); Date = Text(exif, 0x9003); if (Date.Length == 0) Date = Text(main, 0x132);
        var gps = Ifd(Offset(Number(main, 0x8825, 0)));
        byte[] lat, lon; if (gps.TryGetValue(2, out lat) && gps.TryGetValue(4, out lon) && lat.Length >= 24 && lon.Length >= 24)
        {
            double a = Coord(lat), b = Coord(lon); if (Text(gps, 1) == "S") a = -a; if (Text(gps, 3) == "W") b = -b;
            if (Math.Abs(a) <= 90 && Math.Abs(b) <= 180 && !double.IsNaN(a) && !double.IsNaN(b)) Gps = new[] { b, a };
        }
        Datum = Text(gps, 18); byte[] method;
        if (gps.TryGetValue(27, out method) && method.Length > 8 && Encoding.ASCII.GetString(method, 0, 8) == "ASCII\0\0\0") Method = Encoding.ASCII.GetString(method, 8, method.Length - 8).Split('\0')[0].Trim();
        int count = U16(first), next = Offset(U32(first + 2 + count * 12));
        if (next > 0) { var thumb = Ifd(next); int start = Offset(Number(thumb, 0x201, 0)), size = Offset(Number(thumb, 0x202, 0)); if (start > 0 && size > 0 && start <= tiff.Length - size) { Thumbnail = new byte[size]; Buffer.BlockCopy(tiff, start, Thumbnail, 0, size); } }
    }
    Dictionary<int, byte[]> Ifd(int offset)
    {
        var result = new Dictionary<int, byte[]>(); if (offset == 0) return result; int count = U16(offset);
        if (offset > tiff.Length - 2 - count * 12 - 4) throw new InvalidDataException();
        int[] typeSizes = { 0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8 };
        for (int i = 0; i < count; i++)
        {
            int entry = offset + 2 + 12 * i, tag = U16(entry), type = U16(entry + 2); uint n = U32(entry + 4);
            if (type <= 0 || type >= typeSizes.Length || n > 65535) continue; int size = checked((int)n * typeSizes[type]); int start = size <= 4 ? entry + 8 : Offset(U32(entry + 8));
            if (start < 0 || start > tiff.Length - size) continue; var value = new byte[size]; Buffer.BlockCopy(tiff, start, value, 0, size); result[tag] = value;
        }
        return result;
    }
    int U16(int at) { if (at < 0 || at > tiff.Length - 2) throw new InvalidDataException(); return little ? tiff[at] | tiff[at + 1] << 8 : tiff[at] << 8 | tiff[at + 1]; }
    uint U32(int at) { return Bytes32(tiff, at); }
    uint Bytes32(byte[] b, int at) { if (at < 0 || at > b.Length - 4) throw new InvalidDataException(); return little ? (uint)(b[at] | b[at + 1] << 8 | b[at + 2] << 16 | b[at + 3] << 24) : (uint)(b[at] << 24 | b[at + 1] << 16 | b[at + 2] << 8 | b[at + 3]); }
    static int Offset(uint v) { if (v > int.MaxValue) throw new InvalidDataException(); return (int)v; }
    uint Number(Dictionary<int, byte[]> values, int tag, uint fallback) { byte[] v; if (!values.TryGetValue(tag, out v)) return fallback; if (v.Length == 2) return (uint)(little ? v[0] | v[1] << 8 : v[0] << 8 | v[1]); return v.Length >= 4 ? Bytes32(v, 0) : fallback; }
    static string Text(Dictionary<int, byte[]> values, int tag) { byte[] v; return values.TryGetValue(tag, out v) ? Encoding.UTF8.GetString(v).TrimEnd('\0').Trim() : ""; }
    double Rational(byte[] v, int at) { uint d = Bytes32(v, at + 4); return d == 0 ? double.NaN : (double)Bytes32(v, at) / d; }
    double Coord(byte[] v) { return Rational(v, 0) + Rational(v, 8) / 60 + Rational(v, 16) / 3600; }
}
