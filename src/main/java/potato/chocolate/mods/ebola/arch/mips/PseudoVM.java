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
    Jipsy mips = new Jipsy(this, 8<<10); // default to 8KB

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

    Object[] run(Object[] args) throws Exception {
        if(!is_booted) {
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

                        // reset so our first op can be prefetched
                        mips.set_sp(0x4000); // kinda important!
                        mips.set_reset_pc(0x1000);
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

        if(args != null) {
            // TODO: actually queue this
            System.out.printf("Args:");
            for(int i = 0; i < args.length; i++)
                System.out.printf(" %s", args[i].toString());
            System.out.printf("\n");
        }

        // Run some cycles
        try {
            if(!mips.hard_halted) {
                // TODO: adaptive cycle count
                mips.run_cycles(8000000/20);
                if(mips.hard_halted){
                    bsod("Halted");
                } else if(!mips.need_sleep) {
                    // XXX: this could really be, y'know, just a single return
                    // instead of having to create an array
                    //
                    // a fine case of OOP used shittily
                    return new Object[]{(Integer)0};
                }
            }

        } catch (Exception e) {
            bsod("CPU crashed");
            System.err.printf("CPU crashed:\n");
            e.printStackTrace();
        }

	    return null;
    }
 
    void setApiFunction(String name, PseudoNativeFunction value) {
    }
}
