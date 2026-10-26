namespace PadScreen;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Log.Write("界面错误：" + e.Exception.Message);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write("程序错误：" + e.ExceptionObject);
        using var mutex = new Mutex(false, "ai.pad.screen.windows");
        using var show = new EventWaitHandle(false, EventResetMode.AutoReset, "ai.pad.screen.windows.show");
        try
        {
            if (!mutex.WaitOne(0))
            {
                show.Set();
                return;
            }
        }
        catch (AbandonedMutexException)
        {
        }
        Log.Write("已启动");
        Application.Run(new TrayApp());
        Log.Write("已退出");
    }
}
