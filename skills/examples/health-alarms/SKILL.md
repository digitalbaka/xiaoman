---
name: health-alarms
description: 用系统时钟设置健康提醒：喝水、久坐起身、按时休息，只新增不改动已有闹钟
apps: [com.android.deskclock, com.google.android.deskclock, com.android.BBKClock]
channel: gui
confirm_before: [删除, 关闭闹钟]
---
# 健康提醒闹钟

目标：按用户的作息，用手机自带的时钟 App **新建**喝水、起身、休息等提醒；不编辑、不删除、不开关已有闹钟。

1. 先问清楚要提醒什么、几点、是否需要每天重复；用户只说「提醒我喝水」时，默认今天起每小时一次（9:00–21:00）并先复述确认，不要直接批量建几十个。
2. 启动系统时钟 App（原生 com.android.deskclock，Pixel 等 com.google.android.deskclock，vivo 为 com.android.BBKClock），进入「闹钟」页；启动失败再试列表中的其他包。
3. 点「+」逐个新建：设置时间、按用户要求开「重复」，标签用「喝水」「起身活动」「休息」这类短语。用户没要求的铃声、振动保持默认。
   - 滚轮时间控件优先直接输入；只能滑动时每次小幅调整并重新读数，接近目标不要大幅滑动。
4. 保存前核对响铃时间、重复设置与标签；与用户要求不一致就改，改不了用 ask_user 说明。
5. 建完后汇总：一共几个、分别在什么时间、是否每天重复。用户要取消其中某个时，先复述再操作，并只动这次新建的闹钟。

完成后 finish，summary 写新建的提醒数量与时间。找不到时钟 App 或无法保存时用 ask_user 说明。
