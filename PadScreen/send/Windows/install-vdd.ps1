# Installs the Virtual Display Driver that winget only unpacked.
# ARM64 packages do not create the display device by themselves.
param(
    [Parameter(Mandatory = $true)]
    [string]$InfPath
)

$ErrorActionPreference = 'Continue'

function Write-Log([string]$message) {
    $path = Join-Path $env:LOCALAPPDATA 'PadScreen\log.txt'
    $dir = Split-Path $path
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
    Add-Content -Path $path -Value ((Get-Date -Format 'HH:mm:ss ') + $message) -Encoding UTF8
    Write-Output $message
}

if (-not (Test-Path $InfPath)) {
    Write-Log ('找不到驱动文件 ' + $InfPath)
    exit 1
}

Write-Log ('安装虚拟显示驱动 ' + $InfPath)
$driverDir = Split-Path -Parent $InfPath
$cat = Join-Path $driverDir 'mttvdd.cat'
if (Test-Path $cat) {
    try {
        Add-Type -AssemblyName System.Security
        $cms = New-Object System.Security.Cryptography.Pkcs.SignedCms
        $cms.Decode([IO.File]::ReadAllBytes($cat))
        $index = 0
        foreach ($cert in $cms.Certificates) {
            $cer = Join-Path $env:TEMP ('mttvdd-' + $index + '.cer')
            [IO.File]::WriteAllBytes($cer, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
            if ($cert.Subject -eq $cert.Issuer) {
                & certutil -addstore -f Root $cer | Out-Null
                Write-Log ('已信任根证书 ' + $cert.Subject)
            }
            & certutil -addstore -f TrustedPublisher $cer | Out-Null
            $index++
        }
        Write-Log '已信任驱动签名'
    }
    catch {
        Write-Log ('信任签名失败：' + $_.Exception.Message)
    }
}

$out = Join-Path $env:TEMP 'padscreen-pnputil.txt'
$err = Join-Path $env:TEMP 'padscreen-pnputil.err'
$pnp = Start-Process -FilePath "$env:SystemRoot\System32\pnputil.exe" -ArgumentList @('/add-driver', $InfPath, '/install') -WorkingDirectory $driverDir -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
if (-not $pnp.WaitForExit(45000)) {
    Stop-Process -Id $pnp.Id -Force -ErrorAction SilentlyContinue
    Write-Log 'pnputil 超时，驱动安装窗口可能被拦住了'
    exit 2
}
Write-Log ('pnputil 退出码 ' + $pnp.ExitCode)
if (Test-Path $out) {
    $pnpText = Get-Content $out -Raw
    foreach ($row in ($pnpText -split "`r?`n")) {
        if ($row) { Write-Log $row }
    }
    if ($pnpText -match '发布者信任|Authenticode') {
        Write-Log '系统拒绝了驱动签名。ARM 版 Windows 需要先打开测试签名：管理员命令行执行 bcdedit /set testsigning on ，重启后再点一次准备虚拟屏幕。安全启动开着时这一步不会生效。'
    }
}

$source = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class VddInstaller
{
    public static string Install(string infPath, bool createNew)
    {
        var classGuid = new Guid("4d36e968-e325-11ce-bfc1-08002be10318");
        var list = SetupDiCreateDeviceInfoList(ref classGuid, IntPtr.Zero);
        if (list == new IntPtr(-1))
        {
            return "创建设备列表失败 " + Marshal.GetLastWin32Error();
        }
        var notes = new StringBuilder();
        try
        {
            var info = new SP_DEVINFO_DATA();
            info.cbSize = (uint)Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
            if (!createNew)
            {
                notes.Append("设备已在。");
            }
            else if (!SetupDiCreateDeviceInfo(list, "MttVDD", ref classGuid, "Virtual Display Driver", IntPtr.Zero, 1, ref info))
            {
                notes.Append("创建设备失败 " + Marshal.GetLastWin32Error() + "。");
            }
            else
            {
                var bytes = Encoding.Unicode.GetBytes("Root\\MttVDD\0\0");
                if (!SetupDiSetDeviceRegistryProperty(list, ref info, 1, bytes, bytes.Length))
                {
                    notes.Append("写入硬件号失败 " + Marshal.GetLastWin32Error() + "。");
                }
                else if (!SetupDiCallClassInstaller(0x19, list, ref info))
                {
                    notes.Append("注册设备失败 " + Marshal.GetLastWin32Error() + "。");
                }
            }
            bool reboot;
            if (!UpdateDriverForPlugAndPlayDevices(IntPtr.Zero, "Root\\MttVDD", infPath, 1, out reboot))
            {
                notes.Append("更新驱动失败 " + Marshal.GetLastWin32Error() + "。");
            }
            else
            {
                notes.Append(reboot ? "驱动已安装，需要重启。" : "驱动已安装。");
            }
            return notes.ToString();
        }
        finally
        {
            SetupDiDestroyDeviceInfoList(list);
        }
    }

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr SetupDiCreateDeviceInfoList(ref Guid classGuid, IntPtr hwndParent);

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool SetupDiCreateDeviceInfo(IntPtr deviceInfoSet, string deviceName, ref Guid classGuid, string deviceDescription, IntPtr hwndParent, uint creationFlags, ref SP_DEVINFO_DATA deviceInfoData);

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool SetupDiSetDeviceRegistryProperty(IntPtr deviceInfoSet, ref SP_DEVINFO_DATA deviceInfoData, uint property, byte[] propertyBuffer, int propertyBufferSize);

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool SetupDiCallClassInstaller(uint installFunction, IntPtr deviceInfoSet, ref SP_DEVINFO_DATA deviceInfoData);

    [DllImport("setupapi.dll", SetLastError = true)]
    static extern bool SetupDiDestroyDeviceInfoList(IntPtr deviceInfoSet);

    [DllImport("newdev.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool UpdateDriverForPlugAndPlayDevices(IntPtr hwndParent, string hardwareId, string fullInfPath, uint installFlags, out bool rebootRequired);

    [StructLayout(LayoutKind.Sequential)]
    struct SP_DEVINFO_DATA
    {
        public uint cbSize;
        public Guid ClassGuid;
        public uint DevInst;
        public IntPtr Reserved;
    }
}
'@

try {
    Add-Type -TypeDefinition $source -Language CSharp
    $listed = & "$env:SystemRoot\System32\pnputil.exe" /enum-devices /class Display | Out-String
    $createNew = $listed -notmatch 'ROOT\\MTTVDD'
    Write-Log ([VddInstaller]::Install($InfPath, $createNew))
}
catch {
    Write-Log ('创建设备异常：' + $_.Exception.Message)
    exit 3
}

Start-Sleep -Seconds 2
$enum = & "$env:SystemRoot\System32\pnputil.exe" /enum-devices /class Display | Out-String
$instance = $null
foreach ($line in ($enum -split "`r?`n")) {
    if ($line -match 'ROOT\\MTTVDD\\[0-9A-F]+') {
        $instance = $Matches[0].Trim()
    }
    if ($line.Trim().Length -gt 0) {
        Write-Log $line.Trim()
    }
}
if ($instance) {
    & "$env:SystemRoot\System32\pnputil.exe" /restart-device $instance | Out-Null
    Write-Log ('已重启 ' + $instance)
}
else {
    Write-Log '显示设备里还没有 Virtual Display Driver'
}

Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Screen]::AllScreens | ForEach-Object {
    Write-Log ($_.DeviceName + ' ' + $_.Bounds.Width + 'x' + $_.Bounds.Height + ' primary=' + $_.Primary)
}
Write-Log '驱动安装步骤结束'
exit 0
