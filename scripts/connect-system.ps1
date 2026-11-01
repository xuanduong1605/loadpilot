<#
.SYNOPSIS
Guided read-only Kubernetes connection setup for Loadpilot on a VPS.
.DESCRIPTION
Run on the Windows Kubernetes PC. Uses the selected local kubectl context and your
existing VPS SSH access. Never exports your kubeconfig or administrator credentials.
Each mutation is shown and requires confirmation unless its explicit switch is used.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_.@-]+$')][string]$Vps,
    [ValidateRange(1,65535)][int]$SshPort = 22,
    [string]$Context,
    [ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')][string]$Namespace,
    [ValidatePattern('^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$')][string]$Deployment,
    [ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')][string]$Container,
    [string]$ApiUrl,
    [string]$SystemName,
    [ValidateSet('DEVELOPMENT','STAGING','PRODUCTION')][string]$Environment = 'STAGING',
    [ValidateRange(1024,65535)][int]$KubernetesPort = 16443,
    [ValidateRange(1024,65535)][int]$ApiPort = 18080,
    [ValidateRange(1024,65535)][int]$BrowserPort = 18088,
    [ValidateRange(1,65535)][int]$WebPort = 8088,
    [switch]$ProvisionReadOnlyAccess,
    [switch]$PrepareVps,
    [switch]$NoTunnel,
    [switch]$PlanOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne 'Win32NT') { throw 'Run this helper in Windows PowerShell on the Kubernetes PC.' }
foreach ($taskTool in @('kubectl', 'ssh.exe')) {
    if (-not (Get-Command $taskTool -ErrorAction SilentlyContinue)) { throw "Install $taskTool before continuing." }
}
if (@($KubernetesPort, $ApiPort | Select-Object -Unique).Count -ne 2) { throw 'KubernetesPort and ApiPort must differ.' }
function Invoke-Kubectl([string[]]$Arguments, [string]$InputText = '') {
    try {
        $taskArguments = @('--request-timeout=10s') + $(if ($Context) { @('--context', $Context) } else { @() }) + $Arguments
        if ($InputText) { $taskOutput = $InputText | & kubectl @taskArguments 2>$null }
        else { $taskOutput = & kubectl @taskArguments 2>$null }
        if ($LASTEXITCODE -ne 0) { throw 'kubectl failed' }
        return ($taskOutput -join "`n").Trim()
    } catch { throw 'kubectl could not complete the requested operation. Check the context, permissions and cluster availability.' }
}
function Select-Value([string]$Label, [string[]]$Values) {
    if (-not $Values.Count) { throw "No $Label found in the selected scope." }
    if ($Values.Count -eq 1) { Write-Host "$Label`: $($Values[0])"; return $Values[0] }
    for ($taskIndex = 0; $taskIndex -lt $Values.Count; $taskIndex++) { Write-Host "[$($taskIndex + 1)] $($Values[$taskIndex])" }
    $taskChoice = 0
    if (-not [int]::TryParse((Read-Host "Choose $Label number"), [ref]$taskChoice) -or $taskChoice -lt 1 -or $taskChoice -gt $Values.Count) { throw 'Invalid selection. Run the helper again.' }
    return $Values[$taskChoice - 1]
}
if (-not $Context) { $Context = Invoke-Kubectl -Arguments @('config','current-context') }
Write-Host "Kubernetes context: $Context"
if (-not $Namespace) {
    $taskNames = Invoke-Kubectl -Arguments @('get','namespaces','-o','jsonpath={.items[*].metadata.name}')
    $Namespace = Select-Value 'namespace' @($taskNames -split '\s+' | Where-Object { $_ })
}
if (-not $Deployment) {
    $taskNames = Invoke-Kubectl -Arguments @('-n',$Namespace,'get','deployments','-o','jsonpath={.items[*].metadata.name}')
    $Deployment = Select-Value 'Deployment' @($taskNames -split '\s+' | Where-Object { $_ })
}
$taskNames = Invoke-Kubectl -Arguments @('-n',$Namespace,'get','deployment',$Deployment,'-o','jsonpath={.spec.template.spec.containers[*].name}')
$taskContainers = @($taskNames -split '\s+' | Where-Object { $_ })
if (-not $Container) { $Container = Select-Value 'container' $taskContainers }
if ($Container -notin $taskContainers) { throw 'The container is not in the selected Deployment.' }
if ($Namespace -notmatch '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$' -or $Namespace.Length -gt 63 -or $Deployment -notmatch '^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$' -or $Deployment.Length -gt 253 -or $Container -notmatch '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$' -or $Container.Length -gt 63) { throw 'Invalid Kubernetes object name.' }
if (-not $SystemName) { $SystemName = $Deployment }
if (-not $SystemName.Trim() -or $SystemName.Length -gt 100 -or $SystemName -match '[\x00-\x1f]') { throw 'SystemName must contain 1-100 printable characters.' }
if (-not $ApiUrl) { $ApiUrl = Read-Host 'Application HTTP URL reachable from this PC (for example http://localhost:30080/health)' }
$taskApiUri = $null
if (-not [Uri]::TryCreate($ApiUrl, [UriKind]::Absolute, [ref]$taskApiUri) -or $taskApiUri.Scheme -ne 'http' -or $taskApiUri.UserInfo -or $taskApiUri.Query -or $taskApiUri.Fragment -or $taskApiUri.Port -lt 1 -or $ApiUrl.Length -gt 2048) {
    throw 'This tunnel helper needs an HTTP application URL without credentials, query or fragment. Use manual connection setup for an HTTPS application.'
}
if (-not $Vps) { $Vps = Read-Host 'VPS SSH login (for example root@YOUR_VPS_IP)' }
if ($Vps -notmatch '^[A-Za-z0-9_.@-]+$' -or $Vps.StartsWith('-')) { throw 'Use a VPS SSH hostname or user@hostname; specify the port with -SshPort.' }
$taskKubeServer = Invoke-Kubectl -Arguments @('config','view','--minify','-o','jsonpath={.clusters[0].cluster.server}')
$taskKubeUri = $null
if (-not [Uri]::TryCreate($taskKubeServer, [UriKind]::Absolute, [ref]$taskKubeUri) -or $taskKubeUri.Scheme -ne 'https' -or $taskKubeUri.UserInfo -or $taskKubeUri.Query -or $taskKubeUri.Fragment -or $taskKubeUri.AbsolutePath -ne '/') { throw 'Expected a direct HTTPS Kubernetes API endpoint.' }
$taskHost = $taskKubeUri.DnsSafeHost
if ($taskHost -notmatch '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$') { throw 'This helper supports DNS names and IPv4 endpoints.' }
$taskHostIp = $null
if ([Net.IPAddress]::TryParse($taskHost, [ref]$taskHostIp) -and $taskHost -ne '127.0.0.1') { throw 'This tunnel requires a hostname or 127.0.0.1 Kubernetes endpoint. Use direct setup for a different IP endpoint.' }
$taskInsecure = Invoke-Kubectl -Arguments @('config','view','--minify','-o','jsonpath={.clusters[0].cluster.insecure-skip-tls-verify}')
if ($taskInsecure -eq 'true') { throw 'Configure a trusted Kubernetes CA first; TLS verification cannot be disabled.' }
# The output selection is only the public cluster CA, never the users/kubeconfig credentials.
$taskCaData = Invoke-Kubectl -Arguments @('config','view','--minify','--flatten','--raw','-o','jsonpath={.clusters[0].cluster.certificate-authority-data}')
$taskCa = if ($taskCaData) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($taskCaData)) } else { '' }
if ($taskCa -and $taskCa -notmatch '\A\s*(-----BEGIN CERTIFICATE-----\s*[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----\s*)+\z') { throw 'The cluster CA must contain certificates only.' }
$taskSha = [Security.Cryptography.SHA256]::Create()
try { $taskSuffix = -join ($taskSha.ComputeHash([Text.Encoding]::UTF8.GetBytes("$Namespace/$Deployment"))[0..5] | ForEach-Object { $_.ToString('x2') }) } finally { $taskSha.Dispose() }
$taskReader = "loadpilot-$taskSuffix"
$taskMetadata = @{ name=$taskReader; namespace=$Namespace; labels=@{ 'app.kubernetes.io/managed-by'='loadpilot-setup' } }
$taskManifest = @{ apiVersion='v1'; kind='List'; items=@(
    @{ apiVersion='v1'; kind='ServiceAccount'; metadata=$taskMetadata; automountServiceAccountToken=$false },
    @{ apiVersion='rbac.authorization.k8s.io/v1'; kind='Role'; metadata=$taskMetadata; rules=@(
        @{ apiGroups=@('apps'); resources=@('deployments'); resourceNames=@($Deployment); verbs=@('get') },
        @{ apiGroups=@('apps'); resources=@('replicasets'); verbs=@('list') },
        @{ apiGroups=@(''); resources=@('pods'); verbs=@('list') },
        @{ apiGroups=@('metrics.k8s.io'); resources=@('pods'); verbs=@('get','list') }
    ) },
    @{ apiVersion='rbac.authorization.k8s.io/v1'; kind='RoleBinding'; metadata=$taskMetadata;
       subjects=@(@{ kind='ServiceAccount'; name=$taskReader; namespace=$Namespace });
       roleRef=@{ apiGroup='rbac.authorization.k8s.io'; kind='Role'; name=$taskReader } }
) } | ConvertTo-Json -Depth 12 -Compress
Write-Host "System: $SystemName ($Environment), $Namespace/$Deployment, container $Container"
Write-Host "Read-only access: get this Deployment; list ReplicaSets, Pods and PodMetrics in namespace $Namespace. No workload changes, Secrets, exec or logs."
Write-Host "VPS setup: add two exact loopback origins, preserve/create the encryption key, map the Kubernetes hostname to loopback, and restart Loadpilot."
Write-Host "Tunnel: VPS loopback $KubernetesPort -> Kubernetes; VPS loopback $ApiPort -> application. Browser: http://127.0.0.1:$BrowserPort"
if ($PlanOnly) { Write-Host 'PLAN ONLY: no RBAC, token, file, remote operation or tunnel created.'; return }
if (-not $ProvisionReadOnlyAccess -and (Read-Host 'Create/update the described read-only access and request a 1-hour token? [y/N]') -ne 'y') { throw 'Setup cancelled before any mutation.' }
# Never overwrite an unrelated named resource.
foreach ($taskKind in @('serviceaccount','role','rolebinding')) {
    $taskExisting = Invoke-Kubectl -Arguments @('-n',$Namespace,'get',$taskKind,$taskReader,'--ignore-not-found','-o','json')
    if ($taskExisting) {
        $taskObject = $taskExisting | ConvertFrom-Json
        $taskLabel = $taskObject.metadata.PSObject.Properties['labels']
        if (-not $taskLabel -or $taskLabel.Value.'app.kubernetes.io/managed-by' -ne 'loadpilot-setup') { throw 'A resource with this name is not owned by Loadpilot setup. No resource was applied.' }
    }
}
[void](Invoke-Kubectl -Arguments @('apply','-f','-') -InputText $taskManifest)
$taskToken = Invoke-Kubectl -Arguments @('-n',$Namespace,'create','token',$taskReader,'--duration=1h')
if ($taskToken -notmatch '^[A-Za-z0-9._~+/=\-]+$' -or $taskToken.Length -gt 8192) { throw 'The cluster did not issue a valid bounded token.' }
$taskConnectionUrl = "https://${taskHost}:$KubernetesPort"
$taskApiOrigin = "http://127.0.0.1:$ApiPort"
$taskBundle = @{ format='loadpilot-connection-v1'; system=@{ name=$SystemName; environment=$Environment; baseUrl=$taskApiOrigin+$taskApiUri.AbsolutePath; namespace=$Namespace; deployment=$Deployment; container=$Container };
    connection=@{ kubernetesUrl=$taskConnectionUrl; kubernetesCa=$taskCa; kubernetesToken=$taskToken; prometheusUrl=''; prometheusCa=''; prometheusToken='' } } | ConvertTo-Json -Depth 5
if ([Text.Encoding]::UTF8.GetByteCount($taskBundle) -gt 32768) { throw 'Connection data exceeds the 32 KiB import limit.' }
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskDirectory = Join-Path $taskRepo ('secrets/connection-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($taskDirectory)
$taskAcl = New-Object Security.AccessControl.DirectorySecurity
$taskOwner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$taskAcl.SetOwner($taskOwner)
$taskAcl.SetAccessRuleProtection($true, $false)
$taskRule = New-Object Security.AccessControl.FileSystemAccessRule($taskOwner,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
$taskAcl.AddAccessRule($taskRule)
Set-Acl -LiteralPath $taskDirectory -AclObject $taskAcl
$taskFile = Join-Path $taskDirectory 'system.loadpilot.json'
[IO.File]::WriteAllText($taskFile,$taskBundle,(New-Object Text.UTF8Encoding($false)))
$taskToken = $null; $taskBundle = $null
Write-Host "Private connection file: $taskFile"
Write-Host 'Import this file in Loadpilot. It contains a short-lived credential; delete it after saving. Rerun the helper to renew access when the token expires.'
if (-not $PrepareVps) { $PrepareVps = (Read-Host 'Prepare/restart Loadpilot on the VPS over SSH? First time only; choose N if already configured. [y/N]') -eq 'y' }
if ($PrepareVps) {
    $taskPrepare = Join-Path $PSScriptRoot 'prepare-system-connection.sh'
    $taskRemote = if ($Vps.StartsWith('root@')) { 'bash -s --' } else { 'sudo -n bash -s --' }
    # Values in the remote command have already been restricted to safe hostname/port/origin characters.
    # Windows PowerShell appends CRLF when piping to a native process. Normalize
    # the script on the remote side so Bash receives LF even over Windows SSH.
    Get-Content -LiteralPath $taskPrepare -Raw | & ssh.exe -p $SshPort $Vps "tr -d '\r' | $taskRemote $taskHost $KubernetesPort $taskApiOrigin"
    if ($LASTEXITCODE -ne 0) { throw 'VPS preparation failed. Check SSH/sudo and service setup. The private connection file is retained; no tunnel was started.' }
}
if ($NoTunnel) { Write-Host 'NoTunnel selected. Start the SSH forwards separately before checking the connection.'; return }
Write-Host "Keep this terminal open, then visit http://127.0.0.1:$BrowserPort/targets/detail/?new=1 and import the file. Ctrl+C closes the tunnel."
$taskKubeBind = if ($taskHost -eq 'localhost') { 'localhost' } else { '127.0.0.1' }
& ssh.exe -N -T -p $SshPort -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 `
    -R "${taskKubeBind}:${KubernetesPort}:$($taskKubeUri.DnsSafeHost):$($taskKubeUri.Port)" `
    -R "127.0.0.1:${ApiPort}:$($taskApiUri.DnsSafeHost):$($taskApiUri.Port)" `
    -L "127.0.0.1:${BrowserPort}:127.0.0.1:$WebPort" $Vps
if ($LASTEXITCODE -ne 0) { throw 'SSH tunnel ended unsuccessfully. Check SSH forwarding permission, port conflicts and connectivity; rerun the helper when resolved.' }
