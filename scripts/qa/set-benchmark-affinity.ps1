param(
    [Parameter(Mandatory)][int]$ServerProcessId,
    [int]$CoreCount = 4
)
$ErrorActionPreference = 'Stop'
if (-not ('ViewerParrotsQa.CpuTopology' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
namespace ViewerParrotsQa {
    public static class CpuTopology {
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern bool GetLogicalProcessorInformationEx(int relationship, IntPtr buffer, ref uint length);
        public static ulong[] CoreMasks() {
            uint length = 0;
            GetLogicalProcessorInformationEx(0, IntPtr.Zero, ref length);
            if (length == 0) throw new InvalidOperationException("CPU topology unavailable");
            IntPtr buffer = Marshal.AllocHGlobal((int)length);
            try {
                if (!GetLogicalProcessorInformationEx(0, buffer, ref length))
                    throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                var masks = new List<ulong>();
                for (int offset = 0; offset < length;) {
                    int relationship = Marshal.ReadInt32(buffer, offset);
                    int size = Marshal.ReadInt32(buffer, offset + 4);
                    if (size < 8) throw new InvalidOperationException("Invalid CPU topology record");
                    if (relationship == 0) {
                        int groups = (ushort)Marshal.ReadInt16(buffer, offset + 30);
                        if (groups != 1 || Marshal.ReadInt16(buffer, offset + 32 + IntPtr.Size) != 0)
                            throw new NotSupportedException("QA script supports processor group zero only");
                        masks.Add((ulong)Marshal.ReadInt64(buffer, offset + 32));
                    }
                    offset += size;
                }
                return masks.ToArray();
            } finally { Marshal.FreeHGlobal(buffer); }
        }
    }
}
'@
}
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ServerProcessId"
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $processInfo -or $processInfo.CommandLine -notlike "*$projectDirectory*" -or
    $processInfo.CommandLine -notmatch 'fabric.dli.env=server') {
    throw "PID must identify this project's development dedicated server."
}
$coreMasks = [ViewerParrotsQa.CpuTopology]::CoreMasks()
if ($CoreCount -lt 1 -or $coreMasks.Count -lt $CoreCount) { throw 'Insufficient physical CPU cores.' }
[ulong]$selectedMask = 0
foreach ($mask in $coreMasks[0..($CoreCount - 1)]) { $selectedMask = $selectedMask -bor $mask }
$serverProcess = Get-Process -Id $ServerProcessId
$serverProcess.ProcessorAffinity = [IntPtr]::new([long]$selectedMask)
[pscustomobject]@{
    ServerProcessId = $ServerProcessId
    PhysicalCores = $CoreCount
    HostPhysicalCores = $coreMasks.Count
    CoreMasks = @($coreMasks | ForEach-Object { '0x{0:X}' -f $_ })
    SelectedMask = '0x{0:X}' -f $selectedMask
    AppliedMask = '0x{0:X}' -f $serverProcess.ProcessorAffinity.ToInt64()
    Cpu = (Get-CimInstance Win32_Processor).Name
    Os = [Environment]::OSVersion.VersionString
}
