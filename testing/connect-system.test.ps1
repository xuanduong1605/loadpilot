# Native, isolated contract checks. kubectl/SSH are functions; no cluster or VPS is contacted.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskHelper = Join-Path $taskRoot 'scripts/connect-system.ps1'
$global:loadpilotTestCalls = New-Object 'System.Collections.Generic.List[object]'
$global:loadpilotTestMode = 'normal'
function global:kubectl {
    $taskArgs = @($args); $taskText = $taskArgs -join ' '
    $global:loadpilotTestCalls.Add(@{ args=$taskArgs; input=($input -join "`n") })
    $global:LASTEXITCODE=0
    if ($taskText -match 'spec.template.spec.containers') { return 'api' }
    if ($taskText -match 'cluster.server') {
        if ($global:loadpilotTestMode -eq 'ip') { return 'https://10.1.2.3:6443' }
        return 'https://kubernetes.docker.internal:6443'
    }
    if ($taskText -match 'insecure-skip-tls-verify') { if ($global:loadpilotTestMode -eq 'insecure') { return 'true' }; return '' }
    if ($taskText -match 'certificate-authority-data') { return '' }
    if ($taskText -match 'create token') {
        if ($global:loadpilotTestMode -eq 'token-fail') { $global:LASTEXITCODE=1; return '' }
        return 'test-only-short-lived-token'
    }
    if ($taskText -match 'apply -f') { return 'created' }
    if ($taskText -match 'ignore-not-found') {
        if ($global:loadpilotTestMode -eq 'unowned') { return '{"metadata":{"labels":{"app.kubernetes.io/managed-by":"someone-else"}}}' }
        return ''
    }
    throw "Unexpected fake kubectl call: $taskText"
}
function global:ssh.exe {
    $global:loadpilotTestCalls.Add(@{ ssh=@($args); input=($input -join "`n") }); $global:LASTEXITCODE=0
    if ($global:loadpilotTestMode -eq 'ssh-fail') { $global:LASTEXITCODE=255 }
}
function Assert-True($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
$taskDefaults = @{ Vps='root@vps.example.invalid'; Context='fixture'; Namespace='fixture'; Deployment='fixture-api'; Container='api'; ApiUrl='http://localhost:30080/health'; NoTunnel=$true }
function Expect-Failure([string]$Mode,[hashtable]$Extra,[string]$Expected) {
    $global:loadpilotTestMode=$Mode; $global:loadpilotTestCalls.Clear()
    $taskParams=$taskDefaults.Clone(); foreach($taskKey in $Extra.Keys){$taskParams[$taskKey]=$Extra[$taskKey]}
    try { & $taskHelper @taskParams; throw 'Expected failure was absent' }
    catch { if ($_.Exception.Message -notlike "*$Expected*") { throw } }
}
$taskOldDirectories = @(Get-ChildItem (Join-Path $taskRoot 'secrets') -Directory -Filter 'connection-*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
try {
    & $taskHelper @taskDefaults -PlanOnly
    Assert-True (-not @($global:loadpilotTestCalls | Where-Object { $_.ContainsKey('ssh') -or ($_.args -join ' ') -match 'apply|create token' }).Count) 'PlanOnly performed a mutation or contacted SSH'
    Expect-Failure 'normal' @{Container='missing'} 'not in the selected Deployment'
    Expect-Failure 'insecure' @{PlanOnly=$true} 'TLS verification cannot be disabled'
    Expect-Failure 'ip' @{PlanOnly=$true} 'Use direct setup'
    Expect-Failure 'unowned' @{ProvisionReadOnlyAccess=$true} 'not owned by Loadpilot'
    Assert-True (-not @($global:loadpilotTestCalls | Where-Object { ($_.args -join ' ') -match 'apply|create token' }).Count) 'Unowned resource was modified'
    Expect-Failure 'token-fail' @{ProvisionReadOnlyAccess=$true} 'kubectl could not complete'
    $global:loadpilotTestMode='normal'; $global:loadpilotTestCalls.Clear()
    $taskOutput = & $taskHelper @taskDefaults -ProvisionReadOnlyAccess -PrepareVps 6>&1 | Out-String
    Assert-True (-not $taskOutput.Contains('test-only-short-lived-token')) 'Credential was printed'
    $taskNewDirectories = @(Get-ChildItem (Join-Path $taskRoot 'secrets') -Directory -Filter 'connection-*' | Where-Object { $_.FullName -notin $taskOldDirectories })
    Assert-True ($taskNewDirectories.Count -eq 1) 'Expected exactly one private connection directory'
    $taskFile=Join-Path $taskNewDirectories[0].FullName 'system.loadpilot.json'
    $taskBundle=Get-Content -LiteralPath $taskFile -Raw | ConvertFrom-Json
    Assert-True ($taskBundle.format -eq 'loadpilot-connection-v1') 'Missing file version'
    Assert-True ($taskBundle.system.baseUrl -eq 'http://127.0.0.1:18080/health') 'Incorrect API tunnel endpoint'
    Assert-True ($taskBundle.connection.kubernetesUrl -eq 'https://kubernetes.docker.internal:16443') 'Kubernetes TLS hostname was lost'
    Assert-True ($taskBundle.connection.kubernetesToken -eq 'test-only-short-lived-token') 'Token was not exported to the private file'
    Assert-True ((Get-Acl -LiteralPath $taskNewDirectories[0].FullName).AreAccessRulesProtected) 'Directory ACL inherits public access'
    $taskAclRules=@((Get-Acl -LiteralPath $taskFile).Access)
    Assert-True ($taskAclRules.Count -eq 1) 'Private file has unexpected ACL grants'
    $taskApply=@($global:loadpilotTestCalls | Where-Object { $_.ContainsKey('args') -and ($_.args -join ' ') -match 'apply -f' })
    $taskManifest=$taskApply[0].input | ConvertFrom-Json
    Assert-True ($taskManifest.items[0].automountServiceAccountToken -eq $false) 'ServiceAccount auto-mount enabled'
    $taskRole=$taskManifest.items[1]
    Assert-True (($taskRole.rules[0].resourceNames -join ',') -eq 'fixture-api') 'Deployment scope widened'
    Assert-True (-not ($taskRole.rules | ConvertTo-Json -Depth 6).Contains('secrets')) 'RBAC includes Secrets'
    Assert-True (-not ($taskRole.rules | ConvertTo-Json -Depth 6).Contains('patch')) 'RBAC includes mutation'
    Assert-True (@($global:loadpilotTestCalls | Where-Object { $_.ContainsKey('ssh') -and ($_.ssh -join ' ') -match 'bash -s -- kubernetes.docker.internal 16443 http://127.0.0.1:18080' }).Count -eq 1) 'VPS preparation did not use exact destinations'
    Assert-True (-not ($global:loadpilotTestCalls | ConvertTo-Json -Depth 6).Contains('client-key-data')) 'Administrator key requested'
    $taskTunnelParams=$taskDefaults.Clone(); $taskTunnelParams.NoTunnel=$false
    $global:loadpilotTestCalls.Clear()
    [void](& $taskHelper @taskTunnelParams -ProvisionReadOnlyAccess -PrepareVps 6>&1)
    $taskTunnel=@($global:loadpilotTestCalls | Where-Object { $_.ContainsKey('ssh') -and ($_.ssh -join ' ') -match '-N -T' })
    Assert-True ($taskTunnel.Count -eq 1) 'Expected one SSH tunnel'
    $taskTunnelText=$taskTunnel[0].ssh -join ' '
    Assert-True ($taskTunnelText.Contains('127.0.0.1:16443:kubernetes.docker.internal:6443')) 'Kubernetes tunnel is not loopback-scoped'
    Assert-True ($taskTunnelText.Contains('127.0.0.1:18080:localhost:30080')) 'Application tunnel destination changed'
    Assert-True ($taskTunnelText.Contains('127.0.0.1:18088:127.0.0.1:8088')) 'Browser tunnel missing'
    Assert-True ($taskTunnelText.Contains('ExitOnForwardFailure=yes') -and -not $taskTunnelText.Contains('StrictHostKeyChecking=no')) 'SSH failure/trust checks weakened'
    Expect-Failure 'ssh-fail' @{ProvisionReadOnlyAccess=$true;PrepareVps=$true} 'VPS preparation failed'
    Assert-True (-not @($global:loadpilotTestCalls | Where-Object { $_.ContainsKey('ssh') -and ($_.ssh -join ' ') -match '-N -T' }).Count) 'Started a tunnel after VPS preparation failed'
    Write-Output 'PASS: plan-only; workload mismatch; TLS; unsupported IP; unowned RBAC; token failure; narrow manifest; private file; credential redaction; exact VPS setup; loopback forwarding; SSH failure.'
} finally {
    # Delete only directories created by this test inside the repository secrets directory.
    $taskSecrets=[IO.Path]::GetFullPath((Join-Path $taskRoot 'secrets'))+[IO.Path]::DirectorySeparatorChar
    foreach($taskDirectory in @(Get-ChildItem (Join-Path $taskRoot 'secrets') -Directory -Filter 'connection-*' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notin $taskOldDirectories })) {
        if (-not $taskDirectory.FullName.StartsWith($taskSecrets,[StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside test secrets directory' }
        Remove-Item -LiteralPath $taskDirectory.FullName -Recurse -Force
    }
    Remove-Item Function:\kubectl,Function:\ssh.exe -ErrorAction SilentlyContinue
}
