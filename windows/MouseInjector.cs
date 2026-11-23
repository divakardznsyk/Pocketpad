using System.Runtime.InteropServices;

namespace Pocketpad;

/// <summary>Moves the Windows mouse for the phone's touchpad (relative movement + clicks).</summary>
static class MouseInjector
{
    public const uint Left = 0x0002, Right = 0x0008, Middle = 0x0020; // DOWN flags; UP is the next bit

    [StructLayout(LayoutKind.Sequential)]
    struct MouseInput
    {
        public int dx, dy;
        public uint mouseData, flags, time;
        public IntPtr extra;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct Input
    {
        public uint type;
        public MouseInput mi;
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint n, Input[] inputs, int size);

    static void Send(int dx, int dy, uint flags)
    {
        var i = new[] { new Input { type = 0, mi = new MouseInput { dx = dx, dy = dy, flags = flags } } };
        SendInput(1, i, Marshal.SizeOf<Input>());
    }

    public static void Move(int dx, int dy) => Send(dx, dy, 0x0001);

    public static void Button(uint down, bool pressed) => Send(0, 0, pressed ? down : down << 1);
}
