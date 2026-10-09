# Paste box for the Discord bot token: sends it straight to the bridge server over SSH.
# The token is never shown, saved on this PC or printed.
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Spotify Chat bridge'
$form.Size = New-Object System.Drawing.Size(460, 170)
$form.StartPosition = 'CenterScreen'
$form.TopMost = $true
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false

$label = New-Object System.Windows.Forms.Label
$label.Text = 'Paste the Discord bot token (Ctrl+V) and click Save:'
$label.Location = New-Object System.Drawing.Point(12, 14)
$label.AutoSize = $true
$form.Controls.Add($label)

$box = New-Object System.Windows.Forms.TextBox
$box.UseSystemPasswordChar = $true
$box.Location = New-Object System.Drawing.Point(12, 40)
$box.Size = New-Object System.Drawing.Size(420, 24)
$form.Controls.Add($box)

$save = New-Object System.Windows.Forms.Button
$save.Text = 'Save'
$save.Location = New-Object System.Drawing.Point(276, 84)
$save.DialogResult = [System.Windows.Forms.DialogResult]::OK
$form.Controls.Add($save)
$form.AcceptButton = $save

$cancel = New-Object System.Windows.Forms.Button
$cancel.Text = 'Cancel'
$cancel.Location = New-Object System.Drawing.Point(357, 84)
$cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
$form.Controls.Add($cancel)
$form.CancelButton = $cancel

$form.Add_Shown({ $box.Focus() })
if ($form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit }

$token = $box.Text.Trim()
$box.Text = ''
if (-not $token) {
    [System.Windows.Forms.MessageBox]::Show('Nothing was pasted, nothing changed.', 'Spotify Chat bridge') | Out-Null
    exit
}

$result = $token | ssh -o BatchMode=yes spotify-bridge 'sudo spotify-chat-bridge-token' 2>&1
$token = $null
[System.Windows.Forms.MessageBox]::Show(($result -join "`n"), 'Spotify Chat bridge') | Out-Null
