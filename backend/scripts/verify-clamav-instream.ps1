[CmdletBinding()]
param(
    [string] $ScannerHost = '127.0.0.1',
    [int] $ScannerPort = 3310
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ClamInstream {
    param(
        [byte[]] $Payload
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect($ScannerHost, $ScannerPort)
        $stream = $client.GetStream()

        $command = [System.Text.Encoding]::ASCII.GetBytes(('zINSTREAM' + [char] 0))
        $stream.Write($command, 0, $command.Length)

        $length = [BitConverter]::GetBytes(
            [System.Net.IPAddress]::HostToNetworkOrder([int] $Payload.Length))
        $stream.Write($length, 0, $length.Length)
        $stream.Write($Payload, 0, $Payload.Length)

        $end = [byte[]] (0, 0, 0, 0)
        $stream.Write($end, 0, $end.Length)
        $stream.Flush()

        $response = [System.Text.StringBuilder]::new()
        while ($true) {
            $value = $stream.ReadByte()
            if ($value -lt 0 -or $value -eq 0) {
                break
            }
            if ($response.Length -ge 1024) {
                throw 'ClamAV response exceeded the validation limit'
            }
            [void] $response.Append([char] $value)
        }
        return $response.ToString()
    } finally {
        $client.Dispose()
    }
}

$cleanPayload = [System.Text.Encoding]::UTF8.GetBytes('synthetic clean evidence')
$eicarPayload = [System.Text.Encoding]::ASCII.GetBytes(
    'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*')

$cleanVerdict = Invoke-ClamInstream $cleanPayload
$eicarVerdict = Invoke-ClamInstream $eicarPayload

if ($cleanVerdict -notmatch '^stream: OK$') {
    throw "Expected clean verdict OK, received: $cleanVerdict"
}
if ($eicarVerdict -notmatch '^stream: .* FOUND$') {
    throw "Expected EICAR verdict FOUND, received: $eicarVerdict"
}

Write-Output 'ClamAV INSTREAM validation passed (clean=OK, eicar=FOUND).'
