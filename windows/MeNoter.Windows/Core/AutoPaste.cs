using System.Diagnostics;
using System.Runtime.InteropServices;

namespace MeNoter.Core;

/// <summary>
/// Incolla da solo (Ctrl+V) nella finestra dell'app Claude per desktop: porta Claude in primo piano,
/// aspetta che la chat nuova sia pronta e preme Ctrl+V. L'invio resta a te (Invio), così controlli prima.
/// Funziona solo con l'app desktop: con il browser non si sa quale finestra sia Claude.
/// </summary>
public static class AutoPaste
{
    /// <summary>True se ha incollato nella finestra di Claude.</summary>
    public static async Task<bool> PasteIntoClaudeAsync(CancellationToken ct = default)
    {
        IntPtr window = IntPtr.Zero;
        // L'app può metterci qualche secondo ad aprirsi.
        for (int i = 0; i < 40 && window == IntPtr.Zero; i++)
        {
            window = FindClaudeWindow();
            if (window == IntPtr.Zero) await Task.Delay(250, ct);
        }
        if (window == IntPtr.Zero) return false;

        if (IsIconic(window)) ShowWindow(window, SW_RESTORE);
        SetForegroundWindow(window);
        // Tempo per aprire la chat nuova e mettere il cursore nella casella del messaggio.
        await Task.Delay(1800, ct);
        if (!IsClaude(GetForegroundWindow())) return false;
        PressCtrlV();
        return true;
    }

    /// <summary>Secondo Ctrl+V, se Claude è ancora la finestra in primo piano.</summary>
    public static Task<bool> PasteAgainAsync()
    {
        if (!IsClaude(GetForegroundWindow())) return Task.FromResult(false);
        PressCtrlV();
        return Task.FromResult(true);
    }

    private static IntPtr FindClaudeWindow()
    {
        foreach (var p in Process.GetProcessesByName("claude"))
        {
            try
            {
                if (p.MainWindowHandle != IntPtr.Zero) return p.MainWindowHandle;
            }
            catch
            {
                // Processo chiuso nel frattempo.
            }
        }
        return IntPtr.Zero;
    }

    private static bool IsClaude(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return false;
        GetWindowThreadProcessId(hwnd, out uint pid);
        try
        {
            using var p = Process.GetProcessById((int)pid);
            return p.ProcessName.Equals("claude", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private static void PressCtrlV()
    {
        var inputs = new[]
        {
            Key(VK_CONTROL, false), Key(VK_V, false), Key(VK_V, true), Key(VK_CONTROL, true),
        };
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
    }

    private static INPUT Key(ushort vk, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = up ? KEYEVENTF_KEYUP : 0 } },
    };

    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_V = 0x56;
    private const int SW_RESTORE = 9;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT
    {
        public uint uMsg;
        public ushort wParamL;
        public ushort wParamH;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
}
