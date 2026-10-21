namespace PadScreen;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        using var mutex = new Mutex(false, "ai.pad.screen.windows");
        try
        {
            if (!mutex.WaitOne(0))
            {
                return;
            }
        }
        catch (AbandonedMutexException)
        {
        }
        Application.Run(new TrayApp());
    }
}
