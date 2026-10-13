using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;

// Native drawing is available before WebView2 starts. Nothing in the paint path
// opens a file, decodes an image, or waits for the photo library.
internal sealed class LoadingScreen : Control
{
    const double ThrowEnd = 0.48, Bounce1End = 0.76, Bounce2End = 0.97, Bounce3End = 1.13;
    const double LogoEnd = 1.42, FadeDuration = 0.16;
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer { Interval = 16 };
    readonly Stopwatch clock = new Stopwatch();
    readonly SolidBrush blue = new SolidBrush(Color.FromArgb(37, 113, 255));
    readonly SolidBrush ink = new SolidBrush(Color.FromArgb(29, 29, 31));
    readonly SolidBrush muted = new SolidBrush(Color.FromArgb(110, 110, 115));
    readonly SolidBrush badge = new SolidBrush(Color.FromArgb(227, 238, 255));
    readonly SolidBrush shadow = new SolidBrush(Color.FromArgb(18, 33, 63, 111));
    readonly ImageAttributes logoAttributes = new ImageAttributes();
    readonly ColorMatrix logoMatrix = new ColorMatrix();
    readonly StringFormat center = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center, Trimming = StringTrimming.EllipsisCharacter };
    readonly Bitmap logo;
    Font titleFont, statusFont, badgeFont;
    float drawingScale;
    string status = "正在启动生活点滴…";
    bool animate = true, completionRequested, disposed;
    double fadeStarted = -1;
    TaskCompletionSource<int> completion;

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    static extern bool SystemParametersInfo(uint action, uint parameter, [MarshalAs(UnmanagedType.Bool)] out bool value, uint flags);

    internal LoadingScreen()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        BackColor = Color.FromArgb(245, 245, 247);
        logo = LoadLogo(); EnsureDrawingScale(1);
        timer.Tick += delegate
        {
            if (disposed || !Visible) { timer.Stop(); return; }
            UpdateCompletion(); if (Visible) Invalidate();
        };
    }

    internal string Status
    {
        get { return status; }
        set { status = value ?? ""; if (!disposed) Invalidate(); }
    }
    internal double ElapsedSeconds { get { return clock.Elapsed.TotalSeconds; } }

    internal void BeginCycle(bool enableAnimation)
    {
        if (disposed) return;
        // Replacing a cycle releases its old waiter instead of leaving a stale
        // NavigationCompleted continuation waiting forever.
        ReleaseWaiter(); completionRequested = false; fadeStarted = -1;
        animate = enableAnimation && ClientAreaAnimationEnabled(); clock.Restart();
        Visible = true; BringToFront();
        if (animate) timer.Start(); else timer.Stop();
        Invalidate();
    }

    internal Task CompleteAsync()
    {
        if (disposed || !Visible) return Task.FromResult(0);
        if (!animate) { FinishCycle(); return Task.FromResult(0); }
        if (completion == null) completion = new TaskCompletionSource<int>();
        Task task = completion.Task; completionRequested = true;
        UpdateCompletion(); if (Visible) timer.Start();
        return task;
    }

    void UpdateCompletion()
    {
        if (!completionRequested) return;
        double elapsed = ElapsedSeconds;
        if (elapsed < LogoEnd) return;
        if (fadeStarted < 0) fadeStarted = elapsed;
        if (elapsed - fadeStarted >= FadeDuration) FinishCycle();
    }

    void FinishCycle()
    {
        timer.Stop(); clock.Stop(); completionRequested = false;
        Visible = false; ReleaseWaiter();
    }
    void ReleaseWaiter()
    {
        var pending = completion; completion = null;
        if (pending != null) pending.TrySetResult(0);
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (disposed) return;
        if (Visible && clock.IsRunning && animate) timer.Start();
        else if (!Visible) { timer.Stop(); ReleaseWaiter(); }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        double elapsed = animate ? ElapsedSeconds : LogoEnd;
        float opacity = fadeStarted < 0 ? 1 : (float)(1 - Clamp((ElapsedSeconds - fadeStarted) / FadeDuration));
        RenderFrame(e.Graphics, elapsed, opacity, animate);
    }

    // Deterministic snapshots for QA: seconds is the animation clock, pageReady
    // means a page that was already ready when the Logo expansion completed.
    internal void RenderAt(Graphics graphics, double seconds, bool pageReady)
    {
        float opacity = pageReady ? (float)(1 - Clamp((seconds - LogoEnd) / FadeDuration)) : 1;
        graphics.Clear(BackColor); RenderFrame(graphics, Math.Max(0, seconds), opacity, true);
    }

    void RenderFrame(Graphics graphics, double seconds, float opacity, bool useAnimation)
    {
        EnsureDrawingScale(graphics.DpiX / 96f);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
        graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
        float d = drawingScale, cx = Width / 2f, cy = Height * 0.41f;
        // The brand PNG has a 32/512 transparent margin on each edge; 116 px
        // presents the colored tile itself at approximately 102 logical pixels.
        float logoSide = 116 * d, ground = cy + 34 * d;
        float ballX = cx, ballY = ground, ballRadius = 13 * d;
        double morph = Clamp((seconds - Bounce3End) / (LogoEnd - Bounce3End));

        if (useAnimation && seconds < ThrowEnd)
        {
            double t = Clamp(seconds / ThrowEnd);
            float startX = Math.Min(76 * d, Width * 0.09f);
            float startY = Math.Max(62 * d, Height * 0.11f);
            ballX = Lerp(startX, cx, t);
            ballY = Lerp(startY, ground, t) - (float)(4 * Math.Min(112 * d, Height * 0.19f) * t * (1 - t));
            ballRadius = Lerp(8 * d, 13 * d, t);
        }
        else if (useAnimation && seconds < Bounce3End)
        {
            double start; double end; float height;
            if (seconds < Bounce1End) { start = ThrowEnd; end = Bounce1End; height = 100 * d; }
            else if (seconds < Bounce2End) { start = Bounce1End; end = Bounce2End; height = 48 * d; }
            else { start = Bounce2End; end = Bounce3End; height = 19 * d; }
            double t = Clamp((seconds - start) / (end - start));
            ballY = ground - (float)(4 * height * t * (1 - t));
        }
        else if (!useAnimation) morph = 1;

        if (morph > 0) ballY = Lerp(ground, cy, EaseOut(morph));

        float flightHeight = Math.Max(0, ground - ballY);
        float shadowScale = (float)(1 / (1 + flightHeight / (130 * d)));
        float shadowWidth = Lerp(36 * d * shadowScale, 94 * d, EaseOut(morph));
        DrawShadow(graphics, ballX, ground + 24 * d, shadowWidth, 9 * d, opacity * Lerp(0.72f * shadowScale, 0.65f, morph));

        if (morph < 1)
        {
            // Squash only close to a contact; motion remains on the center axis.
            float contact = Math.Max(0, 1 - flightHeight / (14 * d));
            float squash = seconds >= ThrowEnd && seconds < Bounce3End ? contact * 0.16f : 0;
            blue.Color = Color.FromArgb(Alpha(opacity * (float)(1 - EaseOut(morph)), 255), 37, 113, 255);
            graphics.FillEllipse(blue, ballX - ballRadius * (1 + squash), ballY - ballRadius * (1 - squash), 2 * ballRadius * (1 + squash), 2 * ballRadius * (1 - squash));
        }
        if (morph > 0)
        {
            double expand = EaseOutBack(morph);
            float breathing = seconds > LogoEnd && useAnimation ? 1 + (float)Math.Sin((seconds - LogoEnd) * 2.7) * 0.005f : 1;
            float side = Lerp(26 * d, logoSide, expand) * breathing;
            float logoY = Lerp(ground, cy, EaseOut(morph));
            logoMatrix.Matrix33 = opacity * (float)EaseOut(morph);
            logoAttributes.SetColorMatrix(logoMatrix, ColorMatrixFlag.Default, ColorAdjustType.Bitmap);
            var rectangle = new Rectangle((int)Math.Round(cx - side / 2), (int)Math.Round(logoY - side / 2), Math.Max(1, (int)Math.Round(side)), Math.Max(1, (int)Math.Round(side)));
            graphics.DrawImage(logo, rectangle, 0, 0, logo.Width, logo.Height, GraphicsUnit.Pixel, logoAttributes);
        }
        float textOpacity = opacity * (float)EaseOut(morph);
        ink.Color = Color.FromArgb(Alpha(textOpacity, 255), 29, 29, 31);
        graphics.DrawString("生活点滴", titleFont, ink, new RectangleF(16 * d, cy + 67 * d, Math.Max(0, Width - 32 * d), 38 * d), center);
        badge.Color = Color.FromArgb(Alpha(textOpacity, 255), 227, 238, 255);
        blue.Color = Color.FromArgb(Alpha(textOpacity, 255), 0, 106, 231);
        graphics.FillEllipse(badge, cx - 25 * d, cy + 110 * d, 50 * d, 22 * d);
        graphics.DrawString("Beta", badgeFont, blue, new RectangleF(cx - 25 * d, cy + 110 * d, 50 * d, 22 * d), center);
        // Status always belongs to the host. Animation never replaces errors or
        // invents a completion percentage while WebView2/library work is pending.
        muted.Color = Color.FromArgb(Alpha(opacity, 220), 110, 110, 115);
        graphics.DrawString(status, statusFont, muted, new RectangleF(32 * d, cy + 145 * d, Math.Max(0, Width - 64 * d), 48 * d), center);
    }

    void DrawShadow(Graphics graphics, float x, float y, float width, float height, float opacity)
    {
        for (int i = 5; i >= 0; i--)
        {
            shadow.Color = Color.FromArgb(Alpha(opacity, 4 + (5 - i) * 2), 33, 63, 111);
            float pad = i * 2.2f * drawingScale;
            graphics.FillEllipse(shadow, x - width / 2 - pad, y - height / 2 - pad / 3, width + pad * 2, height + pad * 2 / 3);
        }
    }

    void EnsureDrawingScale(float scale)
    {
        if (Math.Abs(drawingScale - scale) < 0.01f) return;
        drawingScale = scale;
        if (titleFont != null) { titleFont.Dispose(); statusFont.Dispose(); badgeFont.Dispose(); }
        titleFont = new Font("Microsoft YaHei UI", 22 * scale, FontStyle.Bold, GraphicsUnit.Pixel);
        statusFont = new Font("Microsoft YaHei UI", 12 * scale, FontStyle.Regular, GraphicsUnit.Pixel);
        badgeFont = new Font("Segoe UI", 11 * scale, FontStyle.Bold, GraphicsUnit.Pixel);
    }

    static bool ClientAreaAnimationEnabled()
    {
        try { bool enabled; return !SystemParametersInfo(0x1042, 0, out enabled, 0) || enabled; }
        catch (DllNotFoundException) { return true; }
        catch (EntryPointNotFoundException) { return true; }
    }
    static Bitmap LoadLogo()
    {
        string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app", "brand", "logo.png");
        try { using (var image = Image.FromFile(path)) return new Bitmap(image); }
        catch (Exception e) { if (!(e is IOException) && !(e is ArgumentException) && !(e is OutOfMemoryException)) throw; }
        // A missing/corrupt optional brand asset never prevents the library from
        // opening. The fallback is created once and all GDI sources are released.
        var fallback = new Bitmap(256, 256, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(fallback))
        using (var blueFill = new SolidBrush(Color.FromArgb(0, 122, 255)))
        using (var white = new SolidBrush(Color.White))
        using (var pen = new Pen(Color.White, 11))
        using (var border = RoundedRectangle(new RectangleF(3, 3, 250, 250), 56))
        using (var photo = RoundedRectangle(new RectangleF(59, 58, 138, 140), 19))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias; g.Clear(Color.Transparent); g.FillPath(blueFill, border);
            g.DrawPath(pen, photo); g.FillEllipse(white, 143, 81, 23, 23);
            g.FillPolygon(white, new[] { new PointF(72, 179), new PointF(112, 124), new PointF(137, 153), new PointF(158, 133), new PointF(187, 179) });
        }
        return fallback;
    }
    static GraphicsPath RoundedRectangle(RectangleF r, float radius)
    {
        var path = new GraphicsPath(); float diameter = radius * 2;
        path.AddArc(r.Left, r.Top, diameter, diameter, 180, 90); path.AddArc(r.Right - diameter, r.Top, diameter, diameter, 270, 90);
        path.AddArc(r.Right - diameter, r.Bottom - diameter, diameter, diameter, 0, 90); path.AddArc(r.Left, r.Bottom - diameter, diameter, diameter, 90, 90); path.CloseFigure(); return path;
    }
    static int Alpha(float amount, int full) { return Math.Max(0, Math.Min(255, (int)Math.Round(amount * full))); }
    static double Clamp(double value) { return Math.Max(0, Math.Min(1, value)); }
    static double EaseOut(double value) { return 1 - Math.Pow(1 - Clamp(value), 4); }
    static double EaseOutBack(double value) { double t = Clamp(value) - 1; return 1 + 2.2 * t * t * t + 1.2 * t * t; }
    static float Lerp(float from, float to, double t) { return from + (to - from) * (float)t; }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !disposed)
        {
            disposed = true; timer.Stop(); timer.Dispose(); clock.Stop(); ReleaseWaiter();
            logo.Dispose(); logoAttributes.Dispose(); blue.Dispose(); ink.Dispose(); muted.Dispose(); badge.Dispose(); shadow.Dispose(); center.Dispose();
            titleFont.Dispose(); statusFont.Dispose(); badgeFont.Dispose();
        }
        base.Dispose(disposing);
    }
}
