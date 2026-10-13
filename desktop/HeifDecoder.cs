using System;
using System.IO;
using ImageMagick;

internal static class HeifDecoder
{
    static readonly Lazy<bool> initialized = new Lazy<bool>(() => { MagickNET.SetNativeLibraryDirectory(AppDomain.CurrentDomain.BaseDirectory); ResourceLimits.Thread = 2; return true; });
    internal static bool Supports(string path) { string ext = Path.GetExtension(path).ToLowerInvariant(); return ext == ".heic" || ext == ".heif"; }
    internal static JpegMetadata Metadata(string path)
    {
        bool ready = initialized.Value;
        using (var image = new MagickImage())
        {
            image.Ping(path, new MagickReadSettings { FrameIndex = 0, FrameCount = 1 });
            var exif = image.GetExifProfile(); var meta = exif == null ? new JpegMetadata() : JpegMetadata.FromExif(exif.ToByteArray());
            // HEIF container transforms are reflected by ImageMagick's orientation; EXIF alone may differ.
            bool rotate = (int)image.Orientation >= 5; meta.Width = (int)(rotate ? image.Height : image.Width); meta.Height = (int)(rotate ? image.Width : image.Height);
            meta.Orientation = 1; return meta;
        }
    }
    internal static byte[] Render(string path, bool thumbnail)
    {
        bool ready = initialized.Value;
        using (var image = new MagickImage(path, new MagickReadSettings { FrameIndex = 0, FrameCount = 1 }))
        {
            image.AutoOrient();
            if (thumbnail) image.Resize(new MagickGeometry(480, 480) { Greater = true });
            if (image.GetColorProfile() != null) image.TransformColorSpace(ColorProfiles.SRGB); else image.ColorSpace = ColorSpace.sRGB;
            image.Quality = 85U;
            if (!thumbnail) image.Settings.SetDefine(MagickFormat.Png, "compression-level", "1");
            // EXIF was indexed separately. Display copies don't need it, and must not apply orientation twice.
            image.Strip(); return image.ToByteArray(thumbnail ? MagickFormat.Jpeg : MagickFormat.Png);
        }
    }
}
