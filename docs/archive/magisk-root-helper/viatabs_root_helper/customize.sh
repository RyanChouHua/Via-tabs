#!/system/bin/sh

ui_print "- Installing ViaTabs Root Helper"
ui_print "- Open ViaTabsAgent once before exporting DB"
ui_print "- Use module action or WebUI to export immediately"

set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm_recursive "$MODPATH/common" 0 0 0755 0644
set_perm "$MODPATH/common/export-via-db.sh" 0 0 0755
set_perm "$MODPATH/common/viatabs-lib.sh" 0 0 0644
