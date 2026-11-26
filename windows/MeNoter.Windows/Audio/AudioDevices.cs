using NAudio.CoreAudioApi;

namespace MeNoter.Audio;

/// <summary>Dispositivo audio di Windows (microfono o uscita).</summary>
public record AudioDevice(string Id, string Name, bool IsDefault)
{
    public override string ToString() => IsDefault ? $"{Name}   (predefinito per le chiamate)" : Name;
}

public static class AudioDevices
{
    /// <summary>Dispositivi attivi: <see cref="DataFlow.Capture"/> per i microfoni, <see cref="DataFlow.Render"/> per le uscite.</summary>
    public static List<AudioDevice> List(DataFlow flow)
    {
        var list = new List<AudioDevice>();
        using var enumerator = new MMDeviceEnumerator();
        string? defaultId = DefaultId(enumerator, flow);
        foreach (var d in enumerator.EnumerateAudioEndPoints(flow, DeviceState.Active))
        {
            try
            {
                list.Add(new AudioDevice(d.ID, d.FriendlyName, d.ID == defaultId));
            }
            finally
            {
                d.Dispose();
            }
        }
        return list.OrderByDescending(d => d.IsDefault).ThenBy(d => d.Name).ToList();
    }

    /// <summary>Dispositivo con questo id, o quello predefinito per le chiamate se non c'è più.</summary>
    public static MMDevice Get(string? id, DataFlow flow)
    {
        var enumerator = new MMDeviceEnumerator();
        if (!string.IsNullOrEmpty(id))
        {
            try
            {
                var d = enumerator.GetDevice(id);
                if (d.State == DeviceState.Active) return d;
            }
            catch
            {
                // Dispositivo scollegato (es. auricolari BT spenti): si usa il predefinito.
            }
        }
        return enumerator.GetDefaultAudioEndpoint(flow, Role.Communications);
    }

    private static string? DefaultId(MMDeviceEnumerator e, DataFlow flow)
    {
        try
        {
            using var d = e.GetDefaultAudioEndpoint(flow, Role.Communications);
            return d.ID;
        }
        catch
        {
            return null;
        }
    }
}
