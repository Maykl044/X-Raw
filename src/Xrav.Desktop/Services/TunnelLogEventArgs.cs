namespace Xrav.Desktop.Services;

public sealed class TunnelLogEventArgs : EventArgs
{
    public TunnelLogEventArgs(string source, string line)
    {
        Source = source;
        Line = line;
    }

    public string Source { get; }
    public string Line { get; }
}
