package potato.chocolate.mods.ebola.arch.mips;

import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import li.cil.oc.api.prefab.AbstractValue;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
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

    int[][] cmp_arg_typ_list = new int[32][2];
    int cmp_call_retcnt = 0;
    byte[][] cmp_call_retarray = new byte[32][];
    int cmp_call_retptr = 0;
    int cmp_call_retlen = 0;

    Map<AbstractValue, Integer> io_handlemap = new HashMap<AbstractValue, Integer>();
    Map<Integer, AbstractValue> io_handlemap_rev = new HashMap<Integer, AbstractValue>();
    int io_handlebeg = 3;
    String[] cmp_list_addr;
    String[] cmp_list_type;
    String[] cmp_list_method;
    byte[] cmp_buf_addr = new byte[64];
    byte[] cmp_buf_type_method = new byte[64];
    byte[] cmp_buf_error = new byte[64];
    int cmp_method_ptr = 0;

    private int read_32_of(byte[] buf, int offs)
    {
        int v = 0;
        if(offs+0 < buf.length) v |= (0xFF&(int)buf[offs+0])<<0;
        if(offs+1 < buf.length) v |= (0xFF&(int)buf[offs+1])<<8;
        if(offs+2 < buf.length) v |= (0xFF&(int)buf[offs+2])<<16;
        if(offs+3 < buf.length) v |= (0xFF&(int)buf[offs+3])<<24;
        return v;
    }

    private void write_32_of_masked(byte[] buf, int offs, int v, int mask)
    {
        if((mask&(0xFF<<0)) != 0 && offs+0 < buf.length) buf[offs+0] = (byte)(v>>0);
        if((mask&(0xFF<<8)) != 0 && offs+1 < buf.length) buf[offs+1] = (byte)(v>>8);
        if((mask&(0xFF<<16)) != 0 && offs+2 < buf.length) buf[offs+2] = (byte)(v>>16);
        if((mask&(0xFF<<24)) != 0 && offs+3 < buf.length) buf[offs+3] = (byte)(v>>24);
    }

    private void parse_retval(int i, Object retval) {
        if(retval == null) {
            this.cmp_arg_typ_list[i][1] = 0;
            this.cmp_arg_typ_list[i][0] = 0;
        } else if(retval instanceof Boolean) {
            this.cmp_arg_typ_list[i][1] = 2;
            this.cmp_arg_typ_list[i][0] = ((boolean)(Boolean)retval ? 1 : 0);
        } else if(retval instanceof String) {
            try {
                cmp_call_retarray[i] = ((String)retval).getBytes("ISO-8859-1");
                this.cmp_arg_typ_list[i][0] = cmp_call_retarray[i].length;
                this.cmp_arg_typ_list[i][1] = 4;
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                this.cmp_arg_typ_list[i][0] = 2;
                this.cmp_arg_typ_list[i][1] = 0;
            }
        } else if(retval instanceof byte[]) {
            this.cmp_arg_typ_list[i][1] = 4;
            cmp_call_retarray[i] = ((byte[])retval);
            this.cmp_arg_typ_list[i][0] = cmp_call_retarray[i].length;
        } else if(retval instanceof Integer) {
            this.cmp_arg_typ_list[i][1] = 6;
            this.cmp_arg_typ_list[i][0] = (Integer)retval;
        } else if(retval instanceof Long) {
            // truncate it anyway
            this.cmp_arg_typ_list[i][1] = 6;
            this.cmp_arg_typ_list[i][0] = (int)(long)(Long)retval;
        } else if(retval instanceof Float) {
            this.cmp_arg_typ_list[i][1] = 8;
            this.cmp_arg_typ_list[i][0] = Float.floatToIntBits((Float)retval);
        } else if(retval instanceof Double) {
            this.cmp_arg_typ_list[i][1] = 8;
            this.cmp_arg_typ_list[i][0] = Float.floatToIntBits((float)(double)(Double)retval);
        } else if(retval instanceof AbstractValue) {
            this.cmp_arg_typ_list[i][1] = 10;
            AbstractValue av = (AbstractValue)retval;
            if(!io_handlemap.containsKey(av)) {
                //System.out.printf("AbstractValue acquire handle %s %d\n", av.toString(),
                //io_handlebeg);
                io_handlemap.put(av, io_handlebeg);
                io_handlemap_rev.put(io_handlebeg, av);
                io_handlebeg++;
            }
            this.cmp_arg_typ_list[i][0] = io_handlemap.get(av);
        } else {
            System.err.printf("TODO: handle type: %s %s\n",
                    retval.getClass().getTypeName(), retval.toString());
            this.cmp_arg_typ_list[i][1] = 0;
            this.cmp_arg_typ_list[i][0] = 1;
        }
    }

    int busReadMask32(int addr_, int mask_) {
        if((addr_&~0x3F) == 0x1FF00200)
            return read_32_of(this.cmp_buf_addr, addr_&0x3C);
        if((addr_&~0x3F) == 0x1FF00240)
            return read_32_of(this.cmp_buf_type_method, addr_&0x3C);
        if((addr_&~0xFF) == 0x1FF00300)
            return this.cmp_arg_typ_list[(addr_>>3)&31][(addr_>>2)&1];

        switch (addr_ & 0xFFFFF) {
            case 0x00004:
                //try
                //{
                // debug port input doesn't exist here
                // it might in future, but right now it does NOT.
                //return 0xFF&(int)'e';
                return -3;
            //return System.in.read();
            //} catch(IOException _e) {
            //return -2;
            //}

            case 0x00020:
                // wall clock, microseconds
                return (int)(1000L*System.currentTimeMillis());
            case 0x00024:
                // wall clock
                return (int)((1000L*System.currentTimeMillis())>>20);

            case 0x00280:
                return this.cmp_method_ptr;
            case 0x00284: {
                // component buffer reset strobe
                Map<String, String> m = machine.components();
                int sz = m.size();
                cmp_list_addr = new String[sz];
                cmp_list_type = new String[sz];
                int i = 0;
                for (Map.Entry<String, String> n : m.entrySet()) {
                    cmp_list_addr[i] = n.getKey();
                    cmp_list_type[i] = n.getValue();
                    i++;
                }
                return cmp_list_addr.length*0x01010101;
            }

            case 0x00286: {
                // method call argument count return
                return cmp_call_retcnt*0x01010101;
            }

            case 0x00287: {
                // event pull strobe
                Signal sig = machine.popSignal();
                if(sig == null)
                    return 0;

                System.out.printf("Event: (%s", sig.name());
                parse_retval(0, sig.name());
                Object[] retvals = sig.args();
                int retcount = Math.min(32, (retvals == null ? 0 : retvals.length)+1);
                for(int i = 1; i < retcount; i++)
                {
                    System.out.printf(", %s", retvals[i-1].toString());
                    parse_retval(i, retvals[i-1]);
                }
                System.out.printf(")\n");

                return retcount*0x01010101;
            }

            case 0x00288:
                return this.cmp_call_retptr;

            case 0x0028C:
                return this.cmp_call_retlen;

            default:
                return 0;
        }
    }

    void busWriteMask32(int addr_, int data_, int mask_) {
        if ((addr_ & ~0x3F) == 0x1FF00200) {
            write_32_of_masked(this.cmp_buf_addr, addr_ & 0x3C, data_, mask_);
            return;
        } else if ((addr_ & ~0x3F) == 0x1FF00240) {
            write_32_of_masked(this.cmp_buf_type_method, addr_ & 0x3C, data_, mask_);
            return;
        }

        if ((addr_ & ~0xFF) == 0x1FF00300) {
            this.cmp_arg_typ_list[(addr_ >> 3) & 31][(addr_ >> 2) & 1] = data_;
            return;
        }

        byte data8 = (byte)((data_)>>((addr_&3)*8));

        switch (addr_ & 0xFFFFF) {
            case 0x00004:
                //System.out.printf("%c", data8);
                mips.outbuf += (char)data8;
                if (mips.outbuf.length() >= mips.OUTBUF_LEN) {
                    // debug output disabled for now.
                    // might be useful for the analyser, though!
                    //System.out.print(this.outbuf);
                    mips.outbuf = "";
                }
                return;

            case 0x00020:
                // sleep strobe
                // TODO: get correct sleep time
                mips.need_sleep = true;
                return;

            case 0x00024:
                mips.c0_cause &= ~(1<<10);
                long tnew = System.currentTimeMillis();
                if ((tnew - mips.interrupt_timer_next) >= 0) {
                    mips.c0_cause |= (1 << 10);

                    mips.interrupt_ready = true;
                    mips.interrupt_timer_next += 1000 / 20;
                    //System.out.printf("timer interrupt refired - status=%08X, cause=%08X\n", c0_status, c0_cause);

                    // don't buffer too many ticks
                    if ((tnew - mips.interrupt_timer_next) >= 2000) {
                        mips.interrupt_timer_next = tnew + 1000 / 20;
                    }
                }
                return;

            case 0x00025:
                if(data8 == 0) {
                    mips.interrupt_timer_enabled = false;
                    mips.c0_cause &= ~(1<<10);
                    //System.out.printf("timer interrupt stopped - status=%08X, cause=%08X\n", c0_status, c0_cause);
                } else if(data8 == 1) {
                    mips.interrupt_timer_enabled = true;
                    mips.interrupt_timer_next = System.currentTimeMillis() + 1000 / 20;
                    //System.out.printf("timer interrupt started - status=%08X, cause=%08X\n", c0_status, c0_cause);
                }
                return;


            case 0x00280:
                this.cmp_method_ptr = data_;
                return;

            case 0x00284: {
                // component search strobe

                // ensure valid
                if (cmp_list_addr == null || data8 < 0 || data8 >= cmp_list_addr.length) {
                    // it isn't
                    cmp_buf_addr[0] = 0;
                    cmp_buf_type_method[0] = 0;
                    return;
                }

                try {
                    byte[] b;
                    b = cmp_list_addr[data8].getBytes("ISO-8859-1");
                    System.arraycopy(b, 0, cmp_buf_addr, 0, Math.min(63, b.length));
                    cmp_buf_addr[Math.min(63, b.length)] = 0;
                    b = cmp_list_type[data8].getBytes("ISO-8859-1");
                    System.arraycopy(b, 0, cmp_buf_type_method, 0, Math.min(63, b.length));
                    cmp_buf_type_method[Math.min(63, b.length)] = 0;
                    cmp_buf_addr[63] = 0;
                    cmp_buf_type_method[63] = 0;
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }

                return;
            }

            case 0x00286: {
                // method call strobe
                String address = new String(this.cmp_buf_addr);
                if(address.indexOf('\u0000') >= 0)
                    address = address.substring(0, address.indexOf('\u0000'));
                String method_name = mips.mem_read_cstr(this.cmp_method_ptr & 0x1FFFFFFF);
                //System.out.printf("Calling %s method %s...\n", address, method_name);
                int arg_count = Math.min(32, data8);
                Object[] args = new Object[arg_count];

                for(int i = 0; i < arg_count; i++) {
                    switch(cmp_arg_typ_list[i][1]) {
                        case 0: // nil
                            args[i] = null;
                            break;
                        case 2: // boolean
                            args[i] = (cmp_arg_typ_list[i][0] != 0);
                            break;
                        case 4: // string
                            args[i] = mips.mem_read_cstr(this.cmp_arg_typ_list[i][0] & 0x1FFFFFFF);
                            break;
                        case 6: // int
                            args[i] = this.cmp_arg_typ_list[i][0];
                            break;
                        case 8: // float
                            args[i] = Float.intBitsToFloat(this.cmp_arg_typ_list[i][0]);
                            break;
                        case 10: // AbstractValue
                            if (io_handlemap_rev.containsKey((Integer)this.cmp_arg_typ_list[i][0])) {
                                AbstractValue av = io_handlemap_rev.get((Integer) this.cmp_arg_typ_list[i][0]);
                                args[i] = av;
                                //System.out.printf("AbstractValue supply handle %s %d\n", av.toString(),
                                //(Integer)this.cmp_arg_typ_list[i][0]);
                            } else {
                                args[i] = null;
                            }
                            break;
                        default:
                            args[i] = null;
                            break;
                    }
                }

                // call method

                // get return count back
                try {
                    Object[] rets;
                    try {
                        rets = machine.invoke(address, method_name, args);
                    } catch(LimitReachedException e) {
                        // jump back to here
                        // FIXME: BLATANT HACK
                        // FIXME: EVEN MORE BLATANT HACK
                        // - REMEMBER TO REVERT PACKAGE LOCALITY ON op_pc, pf0_pc, pf0
                        //System.err.printf("%08X: limit reached: %s -> %s.\n", this.op_pc, address, method_name);
                        mips.pc = mips.op_pc;
                        mips.pf0_pc = mips.pc;
                        mips.pf0 = 0x00000000; // NOP
                        mips.need_sleep = true;
                        mips.sync_call = true;
                        return;
                    }
                    //System.err.printf("%08X: call succeeded: %s -> %s.\n", this.op_pc, address, method_name);

                    this.cmp_call_retcnt = (rets == null ? 0 : rets.length);
                    for (int i = 0; i < this.cmp_call_retcnt; i++) {
                        parse_retval(i, rets[i]);
                    }
                } catch(Exception e) {
                    //System.err.printf("%08X: call failed: %s.\n", this.op_pc, e.getMessage());
                    //System.err.printf("exception!\n");
                    //e.printStackTrace();
                    String err = e.getMessage();
                    if(err == null) err = e.toString();
                    if(err == null) err = "(null?)";
                    byte[] b = err.getBytes();
                    System.arraycopy(b, 0, this.cmp_buf_error, 0, Math.min(63, b.length));
                    this.cmp_buf_error[Math.min(63, b.length)] = 0;
                    this.cmp_buf_error[63] = 0;
                    this.cmp_call_retcnt = -1;
                }

                // Sleep if that was a sync call
                if(mips.sync_call) {
                    mips.need_sleep = true;
                    mips.sync_call = false;
                }
                return;
            }

            case 0x00287: {
                // fetch string strobe

                // ignore if invalid
                if(data8 < 0 || data8 >= this.cmp_call_retcnt
                        || this.cmp_call_retarray[data8] == null)
                    return;

                for(int i = 0; i < Math.min(this.cmp_call_retlen,
                        this.cmp_call_retarray[data8].length); i++)
                {
                    // lop off top few bits for convenience

                    // stop if we aren't even in memory
                    if(((this.cmp_call_retptr+i) & 0x1FFFFFFF) >= mips.ram_bytes)
                        break;

                    // write
                    mips.mem_write_8((this.cmp_call_retptr+i) & 0x1FFFFFFF, 0xA0000000, this.cmp_call_retarray[data8][i]);
                    mips.cycles += 1;
                }

                return;
            }

            case 0x00288:
                this.cmp_call_retptr = data_;
                return;

            case 0x0028C:
                this.cmp_call_retlen = data_;
                return;

            default:
                return;
        }
    }

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
                // YES WE KNOW IT BOOTS JUST LIKE THE LAST 5000 TIMES
                //machine.invoke(addr_gpu, "set", new Object[]{1, h, "BOOT TEST STRING"});
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
                            mips.mem_write_8(0x00001000+i, 0xA0000000, eeprom_data[i]);
                        }

                        // set up jump vector
                        mips.mem_write_32(0x00000000, 0xA0000000, 0x3C1AA000); // LUI k0,     0xA000
                        mips.mem_write_32(0x00000004, 0xA0000000, 0x375A1000); // ORI k0, k0, 0x1000
                        mips.mem_write_32(0x00000008, 0xA0000000, 0x03400008); // JR  k0
                        mips.mem_write_32(0x0000000C, 0xA0000000, 0x00000000); // NOP

                        // set up invalid ops so we can double-fault and BSOD on exception
                        mips.mem_write_32(0x00000080, 0xA0000000, 0xFFFFFFFF);
                        mips.mem_write_32(0x00000100, 0xA0000000, 0xFFFFFFFF);
                        mips.mem_write_32(0x00000180, 0xA0000000, 0xFFFFFFFF);

                        // reset so our first op can be prefetched
                        mips.set_sp(0xA0003F00); // kinda important!
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
        if(mode == 1) { mode = 2; } // TEST

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
}
