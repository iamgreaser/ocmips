package li.cil.oc.example.architecture;

import net.minecraft.nbt.NBTTagCompound;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Node;

/** This is the class you implement; Architecture is from the OC API. */
@Architecture.Name("Pseudolang")
public class PseudoArchitecture implements Architecture {
  private final Machine machine;
 
  private PseudoVM vm;
 
  /** The constructor must have exactly this signature. */
  public PseudoArchitecture(Machine machine) {
    this.machine = machine;
  }
 
  public boolean isInitialized() { return true; }
 
  public void recomputeMemory() {}
 
  public boolean initialize() {
    // Set up new VM here, and register all API callbacks you want to
    // provide to it.
    vm = new PseudoVM();
    vm.setApiFunction("invoke", new PseudoNativeFunction() {
      public Object invoke(Object[] args) {
        final String address = (String)args[0];
        final String method = (String)args[1];
        final Object[] params = (Object[])args[2];
        try {
          return new Object[]{true, machine.invoke(address, method, params)};
        }
        catch (LimitReachedException e) {
          // Perform logic also used to sleep / perform synchronized calls.
          // In this example we'll follow a protocol where if this returns
          // (true, something) the call succeeded, if it returns (false)
          // the limit was reached.
          // The script running in the VM is then supposed to return control
          // to the caller initiating the current execution (e.g. by yielding
          // if supported, or just returning, when in an event driven system).
          return new Object[]{false};
        } catch (Exception e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
          return new Object[]{false};
		}
      }
    });
    vm.setApiFunction("isDirect", new PseudoNativeFunction() {
      public Object invoke(Object[] args) {
        final String address = (String)args[0];
        final String method = (String)args[1];
        final Node node = machine.node().network().node(address);
        if (node instanceof Component) {
          final Component component = (Component) node;
          if (component.canBeSeenFrom(machine.node())) {
            final Callback callback = machine.methods(node.host()).get(method);
            if (callback != null) {
              return callback.direct();
            }
          }
        }
        return false;
      }
    });
    // ... more callbacks.
    return true;
  }
 
  public void close() {
    vm = null;
  }
 
  public ExecutionResult runThreaded(boolean isSynchronizedReturn) {
    // Perform stepping in here. Usually you'll want to resume the VM
    // by passing it the next signal from the queue, but you may decide
    // to allow your VM to poll for signals manually.
    try {
      final Signal signal;
      if (isSynchronizedReturn) {
        // Don't pull signals when we're coming back from a sync call,
        // since we're in the middle of something else!
        signal = null;
      }
      else {
        signal = machine.popSignal();
      }
      final Object[] result;
      if (signal != null) {
        result = vm.run(new Object[]{signal.name(), signal.args()});
      }
      else {
        result = vm.run(null);
      }
 
      // You'll want to define some internal protocol by which to decide
      // when to perform a synchronized call. Let's say we expect the VM
      // to return either a number for a sleep, a boolean to indicate
      // shutdown/reboot and anything else a pending synchronous call.
      if (result != null) {
        if (result[0] instanceof Boolean) {
          return new ExecutionResult.Shutdown((Boolean)result[0]);
        }
        if (result[0] instanceof Integer) {
          return new ExecutionResult.Sleep((Integer)result[0]);
        }
      }
      // If this is returned, the next 'resume' will be runSynchronized.
      // The next call to runThreaded after that call will have the
      // isSynchronizedReturn argument set to true.
      return new ExecutionResult.SynchronizedCall();
    }
    catch (Throwable t) {
      return new ExecutionResult.Error(t.toString());
    }
  }
 
  public void runSynchronized() {
    // Synchronized calls are run from the MC server thread, making it
    // easier for callbacks to interact with the world (because sync is
    // taken care for them by the machine / architecture).
    // This means that if some code in the VM starts a sync call it has
    // to *pause* and relinquish control to the host, where we then
    // switch to sync call mode (see runThreaded), wait for the MC server
    // thread, and then do the actual call. It'd be possible to pass the
    // info required for the call out in runThreaded, keep it around in
    // the arch and do the call directly here. For this example, let's
    // assume the state info is kept inside the VM, and the next resume
    // makes it perform the *actual* call. For some pseudo-code handling
    // this in the VM, see below.
    try {
      vm.run(null);
	} catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
	}
  }
 
  public void onConnect() {}
 
  // Use this to load the VM state, if it can be persisted.
  public void load(NBTTagCompound nbt) {}
 
  // Use this to save the VM state, if it can be persisted.
  public void save(NBTTagCompound nbt) {}
}