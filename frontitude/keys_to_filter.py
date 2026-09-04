# Keys kept by move_frontitude_files.py after a pull.
#
# The Frontitude "Settings" project covers every settings-related screen on
# Kompakt, so a pull brings in far more keys than this app uses. The move
# script drops every line that matches nothing here, exactly as the Settings
# app's own resources module does.
#
# Adding an RFrontitude.string.<key> reference means adding the key here too —
# otherwise the next pull removes the string and the build breaks.

filter_keys = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<resources',
    '</resources>',
    'calendar_accountsync_dialog_body_nothingwillsynchronizewithyour',
    'calendar_accountsync_dialog_body_sharecalendarandeventsbetween',
    'calendar_accountsync_dialog_body_sharecontactsbetweenyourlinked',
    'calendar_accountsync_dialog_body_synchronizenowtoimportyourselected',
    'calendar_accountsync_dialog_body_youwontseedatafromyourgoogle',
    'calendar_accountsync_dialog_button_linkaccount',
    'calendar_accountsync_dialog_button_syncnow',
    'calendar_accountsync_dialog_h1_accountlinked',
    'calendar_accountsync_dialog_h1_disablecalendarsync',
    'calendar_accountsync_dialog_h1_disablecontactsync',
    'calendar_accountsync_dialog_h1_enablecalendarsync',
    'calendar_accountsync_dialog_h1_enablecontactsync',
    'calendar_accountsync_dialog_h1_linkagoogleaccount',
    'calendar_accountsync_dialog_h1_removeaccount',
    'calendar_accountsync_error_dialog_body_linkyouraccountagaintocontinue',
    'calendar_accountsync_error_dialog_body_wecouldntsyncronizewithyyour',
    'calendar_accountsync_error_dialog_button_removeaccount',
    'calendar_accountsync_error_dialog_h1_accountlinkerror',
    'calendar_accountsync_error_dialog_h1_accountsyncfailed',
    'calendar_accountsync_error_h1_couldntconnecttogoogle',
    'calendar_accountsync_error_h1_couldntsetupyouraccount',
    'calendar_accountsync_status_connectingtogoogle',
    'calendar_accountsync_status_lastsync',
    'calendar_accountsync_status_notsyncedyet',
    'calendar_accountsync_status_settingupyouraccount',
    'calendar_accountsync_status_syncisoff',
    'common_button_disable',
    'common_button_skip',
    'common_button_synchronize',
    'common_dialog_button_cancel',
    'common_dialog_button_enable',
    'common_dialog_button_later',
    'common_dialog_button_tryagain',
    'common_error_body_opensettingstocheck',
    'common_error_dialog_body_changestorage',
    'common_error_dialog_h1_storageisfull',
    'common_label_calendar',
    'common_label_contacts',
    'common_label_linkedaccount',
    'common_label_nointernetconnection',
    'common_status_synchronizing',
    'settings_twowaygoogle_body_synchronizeyourcalendarandcontacts',
]
