# 启动 OpenCheatBreaker

在资源管理器中双击同目录的 [START.bat](START.bat)，或者在 OpenCheatBreaker 项目根目录的 PowerShell 中运行：

```powershell
.\START.bat
```

要设置离线玩家 ID，在 `START.bat` 顶部将 `OCB_USERNAME=` 后的名字改成你的游戏名，然后重新启动客户端。启动前也可以设置 `OCB_USERNAME` 环境变量，它会覆盖脚本里的默认名字。

脚本从发布目录启动命中测试客户端。发布文件缺失时会先执行 `gradlew.bat releasePackage`。脚本优先使用本机 `%USERPROFILE%\.jdks\temurin-25.0.4.1`，也可以通过 `JAVA_HOME` 或 `PATH` 提供 JDK 25。

游戏数据保存在 `run\mc`，包括已经导入的 Mod 配置、游戏设置、材质包、光影包和服务器列表。以后在 OpenCheatBreaker 中修改的设置也会保存在这里。重新构建发布包后仍可使用这份游戏数据。
