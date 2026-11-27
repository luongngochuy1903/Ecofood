<#ftl output_format="HTML" auto_esc=true>
<#import "ecofood-email-layout.ftl" as layout>
<@layout.emailLayout>
<p style="margin:0 0 12px;color:#075ACD;font-size:11px;font-weight:bold;letter-spacing:1px;text-align:center;">WELCOME TO YOUR WORKSPACE</p>
<h1 style="margin:0 0 8px;font-size:25px;line-height:1.3;text-align:center;">Verify your email address</h1>
<p style="margin:0 0 28px;color:#64748B;text-align:center;">One step closer to your EcoFood workspace.</p>
<p>Dear <#if user.firstName?has_content>${user.firstName}<#else>there</#if>,</p>
<p>An account has been created for you in <strong>${realmName}</strong>. Please confirm your email address using the button below.</p>
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin:24px 0;background-color:#F4F7FB;border-radius:8px;table-layout:fixed;"><tr><td style="padding:16px;font-size:13px;line-height:1.8;"><strong>Workspace</strong><br>${realmName}<#if user.username?has_content><br><br><strong>Username</strong><br>${user.username}</#if></td></tr></table>
<table role="presentation" align="center" cellspacing="0" cellpadding="0" border="0" style="margin:28px auto;"><tr><td align="center" bgcolor="#075ACD" style="border-radius:6px;mso-padding-alt:13px 22px;"><a href="${link}" style="display:inline-block;padding:13px 22px;font-size:14px;font-weight:bold;color:#FFFFFF;text-decoration:none;line-height:1.5;">Verify Email Address</a></td></tr></table>
<p style="color:#64748B;font-size:13px;">This link expires in ${linkExpirationFormatter(linkExpiration)}. If the button does not work, copy and paste this link into your browser:</p>
<p style="font-size:12px;word-break:break-all;"><a href="${link}" style="color:#075ACD;">${link}</a></p>
<p style="color:#64748B;font-size:13px;">If you did not request this account, you can ignore this email.</p>
<p style="margin:24px 0 0;">Best regards,<br><strong>EcoFood Team</strong></p>
</@layout.emailLayout>
