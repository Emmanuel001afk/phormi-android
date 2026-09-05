package com.uong.phormi

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object PhormiDefaultBrowserController {
 const val REQUEST_CODE=7410
 fun request(activity:Activity):Boolean{
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){val role=activity.getSystemService(RoleManager::class.java);if(role!=null&&role.isRoleAvailable(RoleManager.ROLE_BROWSER)){if(!role.isRoleHeld(RoleManager.ROLE_BROWSER))activity.startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_BROWSER),REQUEST_CODE);return true}}
  return runCatching{activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));true}.getOrDefault(false)
 }
 fun isDefault(context:Context):Boolean=Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q&&context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER)==true
}
