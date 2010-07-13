package processing.app.tools.android;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import processing.app.Preferences;
import processing.app.exec.ProcessRegistry;
import processing.app.exec.StreamPump;
import processing.core.PApplet;

class EmulatorController {
  public static enum State {
    NOT_RUNNING, WAITING_FOR_BOOT, RUNNING
  }

  public static EmulatorController getInstance() {
    return INSTANCE;
  }

  private static final EmulatorController INSTANCE = new EmulatorController();

  private volatile State state = State.NOT_RUNNING;

  public State getState() {
    return state;
  }

  private void setState(final State state) {
    System.out.println("Emulator state: " + state);
    new Exception().printStackTrace(System.out);
    this.state = state;
  }

  /**
   * Blocks until emulator is running, or some catastrophe happens.
   * @throws IOException
   */
  synchronized public void launch() throws IOException {
    if (state != State.NOT_RUNNING) {
      String illegal = "You can't launch an emulator whose state is " + state;
      throw new IllegalStateException(illegal);
    }

    String portString = Preferences.get("android.emulator.port");
    if (portString == null) {
      portString = "5566";
      Preferences.set("android.emulator.port", portString);
    }

    // See http://developer.android.com/guide/developing/tools/emulator.html
    final String[] cmd = new String[] {
      "emulator", 
      "-avd", AVD.defaultAVD.name, 
      "-port", portString, 
      "-no-boot-anim" 
    };
    //System.err.println("EmulatorController: Launching emulator");
    final Process p = Runtime.getRuntime().exec(cmd);
    ProcessRegistry.watch(p);

    // if we've gotten this far, then we've at least succeeded in finding and
    // beginning execution of the emulator, so we are now officially "Launched"
    setState(State.WAITING_FOR_BOOT);

    // TODO need to suppress this warning on OS X
    // 2010-04-13 15:26:56.380 emulator[91699:903] Warning once: This 
    // application, or a library it uses, is using NSQuickDrawView, which has 
    // been deprecated. Apps should cease use of QuickDraw and move to Quartz.
    final String title = PApplet.join(cmd, ' ');
    new StreamPump(p.getInputStream(), "out: " + title).addTarget(System.out)
        .start();
    new StreamPump(p.getErrorStream(), "err: " + title).addTarget(System.err)
        .start();
    final CountDownLatch latch = new CountDownLatch(1);
    new Thread(new Runnable() {
      public void run() {
        try {
          //          System.err.println("EmulatorController: Waiting for boot.");
          while (state == State.WAITING_FOR_BOOT) {
            System.out.println("sleeping for 2 seconds " + new java.util.Date().toString());
            Thread.sleep(2000);
            System.out.println("done sleeping");
            for (final String device : AndroidEnvironment.listDevices()) {
              if (device.contains("emulator")) {
                //                System.err.println("EmulatorController: Emulator booted.");
                setState(State.RUNNING);
                return;
              }
            }
          }
          System.err.println("EmulatorController: Emulator never booted. " + state);
        } catch (final Exception e) {
          System.err.println("While waiting for emulator to boot " + e);
          p.destroy();
        } finally {
          latch.countDown();
        }
      }
    }, "EmulatorController: Wait for emulator to boot").start();
    new Thread(new Runnable() {
      public void run() {
        try {
          try {
            p.waitFor();
            //            final int result = p.waitFor();
            //            System.err
            //                .println("Emulator process exited "
            //                    + ((result == 0) ? "normally" : " with status " + result)
            //                    + ".");
          } catch (final InterruptedException e) {
            System.err.println("Emulator was interrupted.");
          } finally {
            p.destroy();
            ProcessRegistry.unwatch(p);
          }
        } finally {
          setState(State.NOT_RUNNING);
        }
      }
    }, "EmulatorController: Process manager").start();
    try {
      latch.await();
    } catch (final InterruptedException drop) {
      System.err.println("Interrupted while waiting for emulator to launch.");
    }
  }
}