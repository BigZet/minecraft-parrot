param(
    [Parameter(Mandatory)][string]$Command,
    [Parameter(Mandatory)][string]$Password,
    [int]$Port = 25586
)
$ErrorActionPreference = 'Stop'
$connection = [Net.Sockets.TcpClient]::new()
try {
    $connection.Connect('127.0.0.1', $Port)
    $stream = $connection.GetStream()
    $stream.ReadTimeout = 10000
    $stream.WriteTimeout = 10000
    function Read-ExactBytes([int]$Count) {
        $bytes = [byte[]]::new($Count)
        $offset = 0
        while ($offset -lt $Count) {
            $received = $stream.Read($bytes, $offset, $Count - $offset)
            if ($received -eq 0) { throw 'RCON connection closed before complete packet.' }
            $offset += $received
        }
        return ,$bytes
    }
    function Send-RconPacket([int]$Id, [int]$Type, [string]$Body) {
        $bodyBytes = [Text.Encoding]::UTF8.GetBytes($Body)
        $packet = [byte[]]::new($bodyBytes.Length + 14)
        [BitConverter]::GetBytes($bodyBytes.Length + 10).CopyTo($packet, 0)
        [BitConverter]::GetBytes($Id).CopyTo($packet, 4)
        [BitConverter]::GetBytes($Type).CopyTo($packet, 8)
        $bodyBytes.CopyTo($packet, 12)
        $stream.Write($packet, 0, $packet.Length)
    }
    function Read-RconPacket {
        $length = [BitConverter]::ToInt32((Read-ExactBytes 4), 0)
        if ($length -lt 10 -or $length -gt 4194304) { throw 'Invalid RCON packet size.' }
        $packet = Read-ExactBytes $length
        return [pscustomobject]@{
            Id = [BitConverter]::ToInt32($packet, 0)
            Type = [BitConverter]::ToInt32($packet, 4)
            Body = [Text.Encoding]::UTF8.GetString($packet, 8, $length - 10)
        }
    }
    Send-RconPacket 1 3 $Password
    do { $reply = Read-RconPacket } while ($reply.Type -ne 2)
    if ($reply.Id -eq -1) { throw 'RCON authentication rejected.' }
    Send-RconPacket 2 2 $Command
    $reply = Read-RconPacket
    if ($reply.Id -ne 2) { throw 'Unexpected RCON response id.' }
    Write-Output $reply.Body
} finally {
    $connection.Dispose()
}
