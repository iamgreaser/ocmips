package potato.chocolate.mods.ebola.arch.mips;

import li.cil.oc.api.machine.Machine;

import java.util.Map;

/** The VM itself. This is just an example, it's not a "real" interface. */
public class PseudoVM {
    Machine machine;

    String addr_comp = null;
    String addr_gpu = null;
    String addr_screen = null;
    String addr_eeprom = null;

    boolean is_booted = false;
    Jipsy mips = new Jipsy(this, 16);
    int sync_call_accum = 0;

    void bsod(String msg) {
        if(this.mips.hard_halted)
            return;

        try {
            if (addr_gpu != null) {
                machine.invoke(addr_gpu, "setForeground", new Object[]{0xFFFFFF, false});
                Object[] gpuDepthO = machine.invoke(addr_gpu, "getDepth", new Object[]{});
                int depth = 1;
                if (gpuDepthO != null && gpuDepthO.length >= 1 && gpuDepthO[0] instanceof Integer)
                    depth = (Integer) gpuDepthO[0];
                machine.invoke(addr_gpu, "setBackground", new Object[]{0x0000FF, false});
                Object[] gpuSizeO = machine.invoke(addr_gpu, "getResolution",
                        new Object[]{});
                int w = 40;
                int h = 16;
                if (gpuSizeO != null && gpuSizeO.length >= 1 && gpuSizeO[0] instanceof Integer)
                    w = (Integer) gpuSizeO[0];
                if (gpuSizeO != null && gpuSizeO.length >= 2 && gpuSizeO[1] instanceof Integer)
                    h = (Integer) gpuSizeO[1];
                machine.invoke(addr_gpu, "fill", new Object[]{1, 1, w, h, " "});
                machine.invoke(addr_gpu, "set", new Object[]{w / 2 + 1 - 6, h / 2 - 1, "FATAL ERROR:"});
                machine.invoke(addr_gpu, "set", new Object[]{w / 2 + 1 - msg.length() / 2, h / 2 + 1, msg});
            }
        } catch (Exception e) {
            System.err.printf("exception fired in BSOD message - ignored\n");
            e.printStackTrace();
        }
        this.mips.hard_halted = true;
    }

    Object run(int mode) throws Exception {
        if(!is_booted) {
            if(mode != 0) {
                System.out.printf("Waiting for sync call...\n");
                return null;
            }
            machine.beep((short)400, (short)20);
            System.out.printf("Booting!\n");
            System.out.printf("Components: %d\n", this.machine.componentCount());

            this.is_booted = true;

            Map<String, String> m = this.machine.components();
            for(String k: m.keySet()) {
                String v = m.get(k);
                if(v.equals("computer")) this.addr_comp = k;
                if(v.equals("gpu")) this.addr_gpu = k;
                if(v.equals("screen")) this.addr_screen = k;
                if(v.equals("eeprom")) this.addr_eeprom = k;
                System.out.printf(" - %s = %s\n", k, v);
            }

            if(addr_screen != null && addr_gpu != null)
                machine.invoke(addr_gpu, "bind", new Object[]{addr_screen});

            if(addr_gpu != null) {
                machine.invoke(addr_gpu, "setForeground", new Object[]{0xFFFFFF, false});
                machine.invoke(addr_gpu, "setBackground", new Object[]{0x000000, false});
                Object[] gpuSizeO = machine.invoke(addr_gpu, "getResolution",
                        new Object[]{});
                int w = 40;
                int h = 16;
                if(gpuSizeO.length >= 1 && gpuSizeO[0] instanceof Integer)
                    w = (Integer)gpuSizeO[0];
                if(gpuSizeO.length >= 2 && gpuSizeO[1] instanceof Integer)
                    h = (Integer)gpuSizeO[1];
                machine.invoke(addr_gpu, "fill", new Object[]{1, 1, w, h, " "});
                machine.invoke(addr_gpu, "set", new Object[]{1, h, "BOOT TEST STRING"});
            }

            // Clear RAM
            for(int i = 0; i < mips.ram.length; i++) {
                mips.ram[i] = 0;
            }

            // Load EEPROM
            if(addr_eeprom != null) {
                Object[] eeDataO = machine.invoke(addr_eeprom, "get", new Object[]{});
                if(eeDataO != null && eeDataO.length >= 1 && eeDataO[0] instanceof byte[]) {
                    //byte[] eeprom_data = ((String)eeDataO[0]).getBytes("ISO-8859-1");
                    byte[] eeprom_data = (byte[])eeDataO[0];
                    try {
                        for(int i = 0; i < eeprom_data.length; i++) {
                            mips.mem_write_8(0x00001000+i, eeprom_data[i]);
                        }

                        // set up jump vector
                        mips.mem_write_32(0x00000000, 0x3C1AA000); // LUI k0,     0xA000
                        mips.mem_write_32(0x00000004, 0x375A1000); // ORI k0, k0, 0x1000
                        mips.mem_write_32(0x00000008, 0x03400008); // JR  k0
                        mips.mem_write_32(0x0000000C, 0x00000000); // NOP

                        // set up invalid ops so we can double-fault and BSOD on exception
                        mips.mem_write_32(0x00000080, 0xFFFFFFFF);
                        mips.mem_write_32(0x00000100, 0xFFFFFFFF);
                        mips.mem_write_32(0x00000180, 0xFFFFFFFF);

                        // reset so our first op can be prefetched
                        mips.set_sp(0xA0004000); // kinda important!
                        mips.set_reset_pc(0xBFC00000);
                        mips.reset();
                        System.err.printf("BIOS loaded - EEPROM size = %08X, pc = %08X\n"
                                , eeprom_data.length, mips.pc);
                    } catch(Exception e) {
                        System.err.printf("exception fired in EEPROM load\n");
                        e.printStackTrace();
                        bsod("Exception loading EEPROM!");
                    }
                } else {
                    if(eeDataO != null && eeDataO.length >= 1 && eeDataO[0] != null)
                        System.err.printf("the type we actually want is %s\n",
                                eeDataO[0].getClass().getTypeName());
                    else
                        System.err.printf("are you fucking serious\n");
                    bsod("EEPROM data load failed!");
                }
            } else {
                bsod("No EEPROM found!");
            }
        }

        // Run some cycles
        try {
            if(mode == 1) return (Integer)0;

            if(!mips.hard_halted) {
                // TODO: adaptive cycle count
                int cycs = (mode == 0 ? 200 : 40*1000*1000/20);
                mips.run_cycles(cycs);
                if(mips.hard_halted){
                    bsod("Halted");
                } else if(mips.sync_call) {
                    return null;
                } else if(mips.need_sleep) {
                    return (Integer)1;
                }
            }

        } catch (Exception e) {
            bsod("CPU crashed");
            System.err.printf("CPU crashed:\n");
            e.printStackTrace();
        }

	    return (Integer)0;
    }
 
    void setApiFunction(String name, PseudoNativeFunction value) {
    }
}
