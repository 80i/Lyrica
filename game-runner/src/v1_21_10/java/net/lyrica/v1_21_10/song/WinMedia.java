package net.lyrica.v1_21_10.song;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Windows' System Media Transport Controls, called in the game's own process through the WinRT ABI. Every pointer it
 * hands out is a COM reference that the caller gives back with {@link #release}. Use it from one thread, which it
 * joins to the multithreaded apartment.
 *
 * <p>The vtable slots follow the interface definitions in the Windows SDK's {@code windows.media.control.h}: slots 0 to
 * 5 are IUnknown's and IInspectable's, so an interface's own methods start at 6.
 */
final class WinMedia implements AutoCloseable {

  static final int PLAYING = 4;

  private static final String MANAGER_CLASS = "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager";
  private static final Memory IID_MANAGER_STATICS = guid("2050C4EE-11A0-57DE-AED7-C97C70338245");
  private static final Memory IID_ASYNC_INFO = guid("00000036-0000-0000-C000-000000000046");
  private static final Memory IID_STREAM = guid("0000000C-0000-0000-C000-000000000046");
  private static final long TICKS_TO_UNIX = 116_444_736_000_000_000L;
  private static final int MOST_ART_BYTES = 16 << 20;
  private static final int ART_SIZE = 256;

  private final NativeLibrary combase;
  private final Pointer manager;
  private boolean initialized;

  private WinMedia() throws IOException {
    this.combase = NativeLibrary.getInstance("combase");
    int joined = this.combase.getFunction("RoInitialize").invokeInt(new Object[] {1});
    // S_OK or S_FALSE; anything else leaves the thread as it was, with nothing to undo
    this.initialized = joined >= 0;
    if (!this.initialized) {
      throw new IOException("RoInitialize failed: " + hex(joined));
    }

    Pointer statics = null;
    try {
      statics = this.factory(MANAGER_CLASS, IID_MANAGER_STATICS);
      this.manager = this.await(this.outPointer(statics, 6), 3_000L);
    } catch (IOException | RuntimeException e) {
      this.close();
      throw e;
    } finally {
      release(statics);
    }

    if (this.manager == null) {
      this.close();
      throw new IOException("No session manager");
    }
  }

  static WinMedia open() throws IOException {
    return new WinMedia();
  }

  /** The sessions in the order Windows lists them, each to be released by the caller. */
  List<Pointer> sessions() throws IOException {
    List<Pointer> all = new ArrayList<>();
    Pointer view = this.outPointer(this.manager, 7);
    if (view == null) {
      return all;
    }

    try {
      IntByReference size = new IntByReference();
      check(call(view, 7, size));
      for (int at = 0; at < size.getValue(); at++) {
        PointerByReference one = new PointerByReference();
        check(call(view, 6, at, one));
        if (one.getValue() != null) {
          all.add(one.getValue());
        }
      }
    } catch (IOException | RuntimeException e) {
      for (Pointer one : all) {
        release(one);
      }
      throw e;
    } finally {
      release(view);
    }

    return all;
  }

  /** The session Windows calls current, or null. */
  Pointer currentSession() throws IOException {
    return this.outPointer(this.manager, 6);
  }

  String appId(Pointer session) throws IOException {
    return this.outString(session, 6);
  }

  int status(Pointer session) throws IOException {
    Pointer info = this.outPointer(session, 9);
    if (info == null) {
      return 0;
    }

    try {
      IntByReference status = new IntByReference();
      check(call(info, 7, status));
      return status.getValue();
    } finally {
      release(info);
    }
  }

  /** The session's media properties, or null; title 6, artist 9, album 10, thumbnail 15. */
  Pointer properties(Pointer session) throws IOException {
    return this.await(this.outPointer(session, 7), 3_000L);
  }

  String title(Pointer properties) throws IOException {
    return this.outString(properties, 6);
  }

  String artist(Pointer properties) throws IOException {
    return this.outString(properties, 9);
  }

  String album(Pointer properties) throws IOException {
    return this.outString(properties, 10);
  }

  /** The timeline as end, position and last-updated ticks, the first two in 100 ns and the last a Windows date. */
  long[] timeline(Pointer session) throws IOException {
    Pointer timeline = this.outPointer(session, 8);
    if (timeline == null) {
      return new long[3];
    }

    try {
      return new long[] {outLong(timeline, 7), outLong(timeline, 10), outLong(timeline, 11)};
    } finally {
      release(timeline);
    }
  }

  static long unixMillis(long windowsTicks) {
    return (windowsTicks - TICKS_TO_UNIX) / 10_000L;
  }

  /** The cover as a PNG of at most 256 px a side, or null when there is none or it cannot be read. */
  byte[] art(Pointer properties) {
    Pointer reference = null;
    Pointer stream = null;
    Pointer plain = null;
    try {
      reference = this.outPointer(properties, 15);
      if (reference == null) {
        return null;
      }

      stream = this.await(this.outPointer(reference, 6), 3_000L);
      if (stream == null) {
        return null;
      }

      PointerByReference out = new PointerByReference();
      int made = NativeLibrary.getInstance("shcore").getFunction("CreateStreamOverRandomAccessStream")
          .invokeInt(new Object[] {stream, IID_STREAM, out});
      check(made);
      plain = out.getValue();
      return scaled(readAll(plain));
    } catch (IOException | RuntimeException e) {
      return null;
    } finally {
      release(plain);
      release(stream);
      release(reference);
    }
  }

  private static byte[] readAll(Pointer stream) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Memory buffer = new Memory(64 * 1024);
    IntByReference read = new IntByReference();
    while (bytes.size() < MOST_ART_BYTES) {
      read.setValue(0);
      // IStream::Read is slot 3, after IUnknown's three
      int result = call(stream, 3, buffer, (int) buffer.size(), read);
      check(result);
      int count = read.getValue();
      if (count <= 0) {
        break;
      }

      bytes.write(buffer.getByteArray(0, count), 0, count);
    }

    return bytes.toByteArray();
  }

  private static byte[] scaled(byte[] encoded) throws IOException {
    if (encoded.length == 0) {
      return null;
    }

    BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
    if (image == null) {
      return null;
    }

    double scale = Math.min(1.0, (double) ART_SIZE / Math.max(image.getWidth(), image.getHeight()));
    int width = Math.max(1, (int) (image.getWidth() * scale));
    int height = Math.max(1, (int) (image.getHeight() * scale));
    BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D canvas = small.createGraphics();
    try {
      canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      canvas.drawImage(image, 0, 0, width, height, null);
    } finally {
      canvas.dispose();
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(small, "png", out);
    return out.toByteArray();
  }

  /** Waits for an IAsyncOperation, releases it and gives its result, or null for none. */
  private Pointer await(Pointer operation, long timeoutMs) throws IOException {
    if (operation == null) {
      return null;
    }

    Pointer info = null;
    try {
      PointerByReference asked = new PointerByReference();
      check(call(operation, 0, IID_ASYNC_INFO, asked));
      info = asked.getValue();
      IntByReference status = new IntByReference();
      long until = System.currentTimeMillis() + timeoutMs;
      while (true) {
        check(call(info, 7, status));
        if (status.getValue() != 0) {
          break;
        }

        if (System.currentTimeMillis() > until) {
          call(info, 9);
          throw new IOException("timed out");
        }

        try {
          Thread.sleep(5L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          call(info, 9);
          throw new IOException("interrupted");
        }
      }

      if (status.getValue() != 1) {
        throw new IOException("operation ended with status " + status.getValue());
      }

      return this.outPointer(operation, 8);
    } finally {
      release(info);
      release(operation);
    }
  }

  private Pointer factory(String className, Memory iid) throws IOException {
    Pointer name = this.hstring(className);
    try {
      PointerByReference out = new PointerByReference();
      check(this.combase.getFunction("RoGetActivationFactory").invokeInt(new Object[] {name, iid, out}));
      return out.getValue();
    } finally {
      this.combase.getFunction("WindowsDeleteString").invokeInt(new Object[] {name});
    }
  }

  private Pointer hstring(String text) throws IOException {
    PointerByReference out = new PointerByReference();
    check(this.combase.getFunction("WindowsCreateString").invokeInt(new Object[] {new WString(text), text.length(), out}));
    return out.getValue();
  }

  private Pointer outPointer(Pointer object, int slot) throws IOException {
    PointerByReference out = new PointerByReference();
    check(call(object, slot, out));
    return out.getValue();
  }

  private static long outLong(Pointer object, int slot) throws IOException {
    LongByReference out = new LongByReference();
    check(call(object, slot, out));
    return out.getValue();
  }

  private String outString(Pointer object, int slot) throws IOException {
    PointerByReference out = new PointerByReference();
    check(call(object, slot, out));
    Pointer handle = out.getValue();
    if (handle == null) {
      return "";
    }

    try {
      IntByReference length = new IntByReference();
      Pointer chars = (Pointer) this.combase.getFunction("WindowsGetStringRawBuffer")
          .invoke(Pointer.class, new Object[] {handle, length});
      return chars == null || length.getValue() <= 0 ? "" : new String(chars.getCharArray(0, length.getValue()));
    } finally {
      this.combase.getFunction("WindowsDeleteString").invokeInt(new Object[] {handle});
    }
  }

  private static int call(Pointer object, int slot, Object... arguments) {
    Pointer table = object.getPointer(0);
    Function method = Function.getFunction(table.getPointer((long) slot * Native.POINTER_SIZE));
    Object[] all = new Object[arguments.length + 1];
    all[0] = object;
    System.arraycopy(arguments, 0, all, 1, arguments.length);
    return method.invokeInt(all);
  }

  static void release(Pointer object) {
    if (object != null) {
      call(object, 2);
    }
  }

  private static void check(int result) throws IOException {
    if (result < 0) {
      throw new IOException("HRESULT " + hex(result));
    }
  }

  private static String hex(int value) {
    return "0x" + Integer.toHexString(value);
  }

  private static Memory guid(String text) {
    String digits = text.replace("-", "");
    Memory memory = new Memory(16);
    memory.setInt(0, (int) Long.parseLong(digits.substring(0, 8), 16));
    memory.setShort(4, (short) Integer.parseInt(digits.substring(8, 12), 16));
    memory.setShort(6, (short) Integer.parseInt(digits.substring(12, 16), 16));
    for (int at = 0; at < 8; at++) {
      memory.setByte(8 + at, (byte) Integer.parseInt(digits.substring(16 + at * 2, 18 + at * 2), 16));
    }

    return memory;
  }

  @Override
  public void close() {
    if (this.manager != null) {
      release(this.manager);
    }

    if (this.initialized) {
      this.initialized = false;
      this.combase.getFunction("RoUninitialize").invokeVoid(new Object[0]);
    }
  }
}
