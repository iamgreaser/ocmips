package potato.chocolate.mods.ebola.arch.mips;

import li.cil.oc.api.Driver;
import li.cil.oc.api.driver.item.Memory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Node;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** This is the class you implement; Architecture is from the OC API. */
@Architecture.Name("MIPS")
public class PseudoArchitecture implements Architecture {
    private final Machine machine;
 
    private PseudoVM vm;
    private int ram_words;

    /** The constructor must have exactly this signature. */
    public PseudoArchitecture(Machine machine) {
        this.machine = machine;
    }
 
    public boolean isInitialized() { return true; }

    private void updateRamSize() {
        if(vm != null && vm.mips.ram.length != ram_words)
        {
            int[] oldram = vm.mips.ram;
            System.out.printf("RAM size change: %dKB -> %dKB\n", oldram.length/256, ram_words/256);
            assert(ram_words >= 1);
            assert(ram_words <= (64<<18));
            vm.mips.ram = new int[ram_words];
            vm.mips.ram_bytes = ram_words<<2;
            System.arraycopy(oldram, 0, vm.mips.ram, 0,
                    Math.min(oldram.length, vm.mips.ram.length));
        }
    }

    public boolean recomputeMemory(Iterable<ItemStack> components) {
        // Get settings
        // Yes, we have to use reflection for this
        int[] ram_sizes = {192, 256, 384, 512, 768, 1024};
        try {
            Class<?> settings = Class.forName("li.cil.oc.Settings");
            Method settings_get = settings.getMethod("get");
            Object oget = settings_get.invoke(settings);
            Field framsz = settings.getDeclaredField("ramSizes");
            framsz.setAccessible(true);
            Object oramsz = framsz.get(oget);
            if(oramsz instanceof int[]) {
                int[] new_ram_sizes = (int[]) oramsz;
                if (new_ram_sizes != null) {
                    ram_sizes = new_ram_sizes;
                } else {
                    System.err.printf("RAM sizes array is null - using defaults\n");
                }
            } else {
                System.err.printf("RAM sizes array isn't an int[] - using defaults\n");
            }
        } catch(ClassNotFoundException e) {
            System.err.printf("Could not find a class for RAM settings - using defaults\n");
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            System.err.printf("Could not find a particular method for RAM settings - using defaults\n");
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            System.err.printf("InvocationTargetException for RAM - using defaults\n");
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            System.err.printf("IllegalAccessException for RAM - using defaults\n");
            e.printStackTrace();
        } catch (NullPointerException e) {
            System.err.printf("NullPointerException for RAM - using defaults\n");
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            System.err.printf("NoSuchFieldException for RAM - using defaults\n");
            e.printStackTrace();
        }

        int ram_accum = 0;

        // Go through RAM sticks
        for(ItemStack isk : components) {
            li.cil.oc.api.driver.Item d = Driver.driverFor(isk);

            if(d instanceof Memory) {
                Memory m = (Memory)d;
                double amtf = m.amount(isk);
                int amt = ((int)Math.floor(amtf+0.5)) - 1;
                if(amt < 0) amt = 0;
                if(amt > ram_sizes.length - 1) amt = ram_sizes.length - 1;

                int realamt = ram_sizes[amt];
                ram_accum += realamt;

                System.out.printf("Stack RAM amount: %dKB\n", realamt);
            }
        }

        System.out.printf("Total RAM amount: %dKB\n", ram_accum);
        this.ram_words = ram_accum*1024;

        // just do it anyway, it's fun making computers crash
        this.ram_words = (this.ram_words+3)>>2;
        return true;
    }
 
    public boolean initialize() {
        // Set up new VM here, and register all API callbacks you want to
        // provide to it.
        vm = new PseudoVM();
        vm.machine = machine; // Guess where you can shove your abstractions, Java.
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
            final Object result;
            if(vm != null && vm.mips != null) {
                synchronized (vm.mips){
                    updateRamSize();
                }
            }
            result = vm.run(isSynchronizedReturn ? 1 : 2);

            // You'll want to define some internal protocol by which to decide
            // when to perform a synchronized call. Let's say we expect the VM
            // to return either a number for a sleep, a boolean to indicate
            // shutdown/reboot and anything else a pending synchronous call.
            if (result != null) {
                if (result instanceof Boolean) {
                    return new ExecutionResult.Shutdown((Boolean)result);
                }
                if (result instanceof Integer) {
                    return new ExecutionResult.Sleep((Integer)result);
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
            if(vm != null && vm.mips != null) {
                synchronized (vm.mips){
                    updateRamSize();
                }
            }
            vm.run(0);
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

    // ...not sure what this does but it's required for OC 1.6
    public void onSignal() {}
}