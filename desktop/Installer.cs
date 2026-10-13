using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class Setup
{
    internal const string Product = "生活点滴";
    internal const string RegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\LifeMoments";
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    [STAThread] static int Main(string[] args)
    {
        Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            if (args.Contains("--uninstall"))
            {
                string dir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
                if (MessageBox.Show("卸载生活点滴？照片和用户索引会保留。", Product, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return 0;
                string copy = Path.Combine(Path.GetTempPath(), "LifeMoments-Uninstall-" + Guid.NewGuid().ToString("N") + ".exe");
                File.Copy(Application.ExecutablePath, copy);
                Process.Start(new ProcessStartInfo(copy, "--uninstall-target \"" + dir + "\"") { UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
                return 0;
            }
            string uninstall = Arg(args, "--uninstall-target");
            if (uninstall != null) { Uninstall(uninstall); return 0; }
            string target = Arg(args, "--dir") ?? DefaultTarget();
            if (args.Contains("--silent")) { Install(target); return 0; }
            Application.Run(new SetupWindow(target)); return 0;
        }
        catch (Exception e)
        {
            File.WriteAllText(Path.Combine(Path.GetTempPath(), "LifeMoments-setup-error.txt"), e.ToString());
            if (!args.Contains("--silent")) MessageBox.Show(e.Message, Product + "安装失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
    static string Arg(string[] args, string key) { int i = Array.IndexOf(args, key); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
    static string DefaultTarget()
    {
        using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RegistryPath))
        {
            string installed = key == null ? "" : Convert.ToString(key.GetValue("InstallLocation"));
            if (!String.IsNullOrWhiteSpace(installed) && Directory.Exists(installed)) return installed;
        }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", Product);
    }
    static string Target(string value)
    {
        string target = Path.GetFullPath(value).TrimEnd(Path.DirectorySeparatorChar);
        if (target == Path.GetPathRoot(target).TrimEnd(Path.DirectorySeparatorChar) || target.IndexOf('"') >= 0) throw new InvalidOperationException("请选择独立的应用目录。");
        string photos = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
        string settingsFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LifeMoments", "settings.json");
        if (File.Exists(settingsFile))
        {
            var settings = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(settingsFile));
            object savedSource; if (settings.TryGetValue("source", out savedSource) && !String.IsNullOrWhiteSpace(Convert.ToString(savedSource))) photos = Convert.ToString(savedSource);
        }
        if (!String.IsNullOrWhiteSpace(photos))
        {
            photos = Path.GetFullPath(photos).TrimEnd(Path.DirectorySeparatorChar);
            if (target.Equals(photos, StringComparison.OrdinalIgnoreCase) || target.StartsWith(photos + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("不能安装到照片目录。");
        }
        return target;
    }
    static string Child(string root, string relative)
    {
        string path = Path.GetFullPath(Path.Combine(root, relative));
        if (!path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("安装包文件路径无效。");
        return path;
    }
    internal static void Install(string value)
    {
        string target = Target(value);
        if (Directory.Exists(target) && Directory.EnumerateFileSystemEntries(target).Any() && !File.Exists(Path.Combine(target, "installed-files.json"))) throw new InvalidOperationException("目标目录已有其他内容，请选择新的目录。");
        Directory.CreateDirectory(target);
        var files = new List<string>();
        using (Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
        using (var zip = new ZipArchive(resource, ZipArchiveMode.Read))
        {
            foreach (var entry in zip.Entries)
            {
                if (entry.FullName.EndsWith("/")) continue;
                string relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                string path = Child(target, relative);
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                using (Stream input = entry.Open()) using (Stream output = File.Create(path)) input.CopyTo(output);
                files.Add(relative);
            }
        }
        string uninstaller = Path.Combine(target, "卸载生活点滴.exe");
        if (!Application.ExecutablePath.Equals(uninstaller, StringComparison.OrdinalIgnoreCase)) File.Copy(Application.ExecutablePath, uninstaller, true);
        files.Add("卸载生活点滴.exe");
        File.WriteAllText(Path.Combine(target, "installed-files.json"), Json.Serialize(files), new UTF8Encoding(false));
        string exe = Path.Combine(target, Product + ".exe");
        CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Product + ".lnk"), exe);
        string start = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), Product);
        Directory.CreateDirectory(start);
        CreateShortcut(Path.Combine(start, Product + ".lnk"), exe);
        CreateShortcut(Path.Combine(start, "卸载生活点滴.lnk"), uninstaller, "--uninstall");
        using (RegistryKey key = Registry.CurrentUser.CreateSubKey(RegistryPath))
        {
            key.SetValue("DisplayName", Product + " (Beta)");
            key.SetValue("DisplayVersion", "0.3.0-beta.9");
            key.SetValue("Publisher", "生活点滴");
            key.SetValue("InstallLocation", target);
            key.SetValue("DisplayIcon", Path.Combine(target, "app", "brand", "logo.ico"));
            key.SetValue("UninstallString", "\"" + uninstaller + "\" --uninstall");
            key.SetValue("NoModify", 1, RegistryValueKind.DWord);
            key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        }
    }
    static void CreateShortcut(string path, string target, string args = "")
    {
        Type type = Type.GetTypeFromProgID("WScript.Shell"); object shell = Activator.CreateInstance(type);
        object shortcut = type.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { path });
        Type sc = shortcut.GetType();
        sc.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { target });
        sc.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { Path.GetDirectoryName(target) });
        sc.InvokeMember("Arguments", BindingFlags.SetProperty, null, shortcut, new object[] { args });
        sc.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { Path.Combine(Path.GetDirectoryName(target), "app", "brand", "logo.ico") + ",0" });
        sc.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, new object[0]);
        System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shortcut);
        System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shell);
    }
    static void Uninstall(string value)
    {
        string target = Target(value), manifest = Path.Combine(target, "installed-files.json");
        if (!File.Exists(manifest) || !File.Exists(Path.Combine(target, Product + ".exe"))) throw new InvalidOperationException("这不是生活点滴安装目录。");
        using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RegistryPath))
        { if (key == null || !target.Equals(Convert.ToString(key.GetValue("InstallLocation")), StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("安装记录不匹配。"); }
        string[] files = Json.Deserialize<string[]>(File.ReadAllText(manifest));
        foreach (string name in files) { string file = Child(target, name); if (File.Exists(file)) File.Delete(file); }
        File.Delete(manifest);
        foreach (string dir in Directory.GetDirectories(target, "*", SearchOption.AllDirectories).OrderByDescending(d => d.Length)) if (!Directory.EnumerateFileSystemEntries(dir).Any()) Directory.Delete(dir);
        if (!Directory.EnumerateFileSystemEntries(target).Any()) Directory.Delete(target);
        string desktop = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Product + ".lnk");
        if (File.Exists(desktop)) File.Delete(desktop);
        string start = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), Product);
        foreach (string name in new[] { Product + ".lnk", "卸载生活点滴.lnk" }) { string path = Path.Combine(start, name); if (File.Exists(path)) File.Delete(path); }
        if (Directory.Exists(start) && !Directory.EnumerateFileSystemEntries(start).Any()) Directory.Delete(start);
        Registry.CurrentUser.DeleteSubKeyTree(RegistryPath, false);
    }
}

internal sealed class SetupWindow : Form
{
    readonly TextBox directory = new TextBox();
    readonly Label status = new Label();
    readonly Button install = new Button();
    public SetupWindow(string target)
    {
        Text = "安装生活点滴 Beta"; ClientSize = new Size(600, 310); FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; StartPosition = FormStartPosition.CenterScreen; Font = new Font("Microsoft YaHei UI", 10); BackColor = Color.White;
        Controls.Add(new Label { Text = "生活点滴", Font = new Font("Microsoft YaHei UI", 23), AutoSize = true, Location = new Point(30, 25) });
        Controls.Add(new Label { Text = "桌面版 0.3.0-beta.9 · 照片、人物、相册与地点", AutoSize = true, Location = new Point(33, 83) });
        Controls.Add(new Label { Text = "安装位置", AutoSize = true, Location = new Point(33, 133) });
        directory.Text = target; directory.SetBounds(33, 162, 432, 30); Controls.Add(directory);
        var browse = new Button { Text = "浏览…", Location = new Point(474, 160), Size = new Size(91, 32) };
        browse.Click += delegate { using (var d = new FolderBrowserDialog { SelectedPath = directory.Text }) { if (d.ShowDialog(this) == DialogResult.OK) directory.Text = Path.Combine(d.SelectedPath, "生活点滴"); } }; Controls.Add(browse);
        status.SetBounds(33, 206, 530, 35); status.Text = "索引单独保存，原照片保持只读。"; Controls.Add(status);
        install.Text = "安装"; install.SetBounds(452, 254, 112, 34); Controls.Add(install);
        install.Click += async delegate
        {
            if (install.Text == "完成") { Close(); return; }
            string targetPath = directory.Text;
            install.Enabled = false; directory.Enabled = false; browse.Enabled = false; ControlBox = false; status.Text = "正在安装…";
            try { await Task.Run(() => Setup.Install(targetPath)); status.Text = "安装完成，已创建桌面快捷方式。"; install.Text = "完成"; }
            catch (Exception e) { status.Text = "安装失败：" + e.Message; directory.Enabled = true; browse.Enabled = true; }
            install.Enabled = true; ControlBox = true;
        };
    }
}
