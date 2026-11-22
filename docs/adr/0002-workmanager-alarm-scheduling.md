# WorkManager + AlarmManager for Schedules

WorkManager alone is inexact under Doze and can miss 9AM sharp times, while a always-on ForegroundService drains battery. We store Schedules in Room and trigger via WorkManager periodic work plus AlarmManager exact alarms with reboot reschedule, balancing punctuality and battery.
