# Run once on your Windows PC with a JDK and GitHub CLI installed.
$ErrorActionPreference = 'Stop'
$repo = 'futsss47-oss/Android'
Get-Command keytool, gh -ErrorAction Stop | Out-Null
gh auth status
if ($LASTEXITCODE) { throw 'Run gh auth login first.' }
$existing = gh secret list --repo $repo --json name | ConvertFrom-Json
if ($LASTEXITCODE) { throw 'Cannot read repository secret names.' }
$dir = Join-Path $env:USERPROFILE 'AIPhoneAssistant-Signing'
$store = Join-Path $dir 'assistant.p12'
if (($existing.name -contains 'ASSISTANT_KEYSTORE_BASE64') -and !(Test-Path $store)) {
 throw 'Signing already configured. Restore the original keystore from backup.'
}
New-Item -Type Directory -Force $dir | Out-Null
$secret = Read-Host 'Keystore password (save this in your password manager)' -AsSecureString
$pw = [System.Net.NetworkCredential]::new('', $secret).Password
if ($pw.Length -lt 8) { throw 'Use at least 8 characters.' }
$env:ASSISTANT_SETUP_PASSWORD = $pw
try {
 if (!(Test-Path $store)) {
  keytool -genkeypair -storetype PKCS12 -keystore $store -storepass:env ASSISTANT_SETUP_PASSWORD -keypass:env ASSISTANT_SETUP_PASSWORD -alias assistant -keyalg RSA -keysize 3072 -validity 12000 -dname 'CN=AI Phone Assistant'
  if ($LASTEXITCODE) { throw 'Key generation failed.' }
 }
 keytool -list -keystore $store -alias assistant -storepass:env ASSISTANT_SETUP_PASSWORD
 if ($LASTEXITCODE) { throw 'Wrong password or alias.' }
 if ($existing.name -contains 'ASSISTANT_KEYSTORE_BASE64') { throw 'A release key already exists on GitHub. No secrets were replaced.' }
 $values = [ordered]@{ ASSISTANT_KEYSTORE_PASSWORD=$pw; ASSISTANT_KEY_PASSWORD=$pw; ASSISTANT_KEY_ALIAS='assistant'; ASSISTANT_KEYSTORE_BASE64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($store)) }
 foreach ($item in $values.GetEnumerator()) {
  $item.Value | gh secret set $item.Key --repo $repo
  if ($LASTEXITCODE) { throw "Failed to set $($item.Key)" }
 }
 Write-Host "Back up $store and its password. Rerun the latest assistant build."
} finally {
 Remove-Item Env:ASSISTANT_SETUP_PASSWORD -ErrorAction SilentlyContinue
 Remove-Variable pw, values -ErrorAction SilentlyContinue
}
