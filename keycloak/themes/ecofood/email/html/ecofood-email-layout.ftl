<#ftl output_format="HTML" auto_esc=true>
<#macro emailLayout>
<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="margin:0;padding:0;background-color:#F4F7FB;font-family:Arial,Helvetica,sans-serif;color:#17263B;">
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color:#F4F7FB;"><tr><td align="center" style="padding:24px 12px;">
<!--[if mso]><table role="presentation" width="600" cellspacing="0" cellpadding="0"><tr><td><![endif]-->
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width:600px;background-color:#FFFFFF;border:1px solid #E5EAF1;border-radius:10px;table-layout:fixed;">
<tr><td align="center" style="padding:28px 24px;background-color:#075ACD;color:#FFFFFF;border-radius:9px 9px 0 0;"><p style="margin:0;font-size:24px;font-weight:bold;line-height:1.4;">EcoFood</p><p style="margin:6px 0 0;font-size:12px;line-height:1.5;">Account &amp; Workspace</p></td></tr>
<tr><td style="padding:32px 24px;font-size:14px;line-height:1.8;overflow-wrap:anywhere;word-break:break-word;"><#nested></td></tr>
<tr><td align="center" style="padding:24px;border-top:1px solid #E5EAF1;color:#64748B;font-size:12px;line-height:1.8;"><p style="margin:0 0 8px;font-weight:bold;">Powered by EcoFood</p><p style="margin:0;">This is an automated email. Please do not reply directly to this message.</p><p style="margin:12px 0 0;">&copy; ${.now?string('yyyy')} EcoFood. All rights reserved.</p></td></tr>
</table><!--[if mso]></td></tr></table><![endif]-->
</td></tr></table></body></html>
</#macro>
