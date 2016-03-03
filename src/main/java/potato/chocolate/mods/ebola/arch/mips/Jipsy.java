package potato.chocolate.mods.ebola.arch.mips;

import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Signal;
import li.cil.oc.api.prefab.AbstractValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by GreaseMonkey on 2/22/16.
 */
public class Jipsy {
    private String outbuf = "";

    int[] ram;
    int[] regs;
    int rlo, rhi;
    int ram_bytes;
    int pc;
    int cycles;
    private int cycle_wait;
    private int pf0;
    private int pf0_pc;
    private int op_pc;
    private int reset_pc = 0x00001000;
    boolean hard_halted = false;
    boolean need_sleep = false;
    boolean sync_call = false;

    public static final int EX_Int = 0x00; // Interrupt
    public static final int EX_Mod = 0x01; // TLB modification exception
    public static final int EX_TLBL = 0x02; // TLB exception (load/ifetch)
    public static final int EX_TLBS = 0x03; // TLB exception (store)
    public static final int EX_AdEL = 0x04; // Address error exception (load/ifetch)
    public static final int EX_AdES = 0x05; // Address error exception (store)
    public static final int EX_IBE = 0x06; // Bus error exception (ifetch)
    public static final int EX_DBE = 0x07; // Bus error exception (data: load/store)
    public static final int EX_Sys = 0x08; // Syscall exception
    public static final int EX_Bp = 0x09; // Breakpoint exception
    public static final int EX_RI = 0x0A; // Reserved instruction exception
    public static final int EX_CpU = 0x0B; // Coprocessor unusable exception
    public static final int EX_Ov = 0x0C; // Arithmetic overflow exception

    int c0_index; // 0
    int c0_entrylo; // 2
    int c0_context; // 4
    int c0_vaddr; // 8
    int c0_entryhi; // 10
    int c0_status; // 12
    int c0_cause; // 13
    int c0_epc; // 14

    private PseudoVM vm;
    static final int OUTBUF_LEN = 1;
    private boolean interrupt_ready;
    private boolean interrupt_timer_enabled = false;
    private long interrupt_timer_next = 0;

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
    int[][] cmp_arg_typ_list = new int[32][2];

    // 0 = entrylo, 1 = entryhi, 2 = next in queue, 3 = previous in queue
    int[][] tlb_entries = new int [64][4];
    int tlb_entry_most_recent = 0;

    int cmp_call_retcnt = 0;
    byte[][] cmp_call_retarray = new byte[32][];
    int cmp_call_retptr = 0;
    int cmp_call_retlen = 0;
    private boolean double_fault_detect = false;
    private boolean pf0_double_fault_detect = false;
    private int pf0_fault = -1;
    private int pf0_fault_pc = 0;
    private int bd_detect = -1;

    public void use_cycles(int count)
    {
        assert(count >= 0);
        this.cycles += count;
    }

    public void set_sp(int sp)
    {
        this.regs[29] = sp;
    }

    public void set_gp(int gp)
    {
        this.regs[28] = gp;
    }

    public void set_reset_pc(int pc) { this.reset_pc = pc; }

    private void reset_tlb()
    {
        int i;

        for(i = 0; i < 64; i++) {
            tlb_entries[i][0] = 0x00000000;
            tlb_entries[i][1] = 0x00000000;
            tlb_entries[i][2] = (i+1)%64;
            tlb_entries[i][3] = (i+64-1)%64;
        }

        tlb_entry_most_recent = 0;
    }

    private void touch_tlb(int idx)
    {
        // If most recent, DO NOT DO ANYTHING.
        if(idx == tlb_entry_most_recent)
            return;

        // Get previous most recent
        // System.out.printf("TLB touch: %02X: %08X <- %08X\n"
                // , idx, tlb_entries[idx][0], tlb_entries[idx][1]);
        int previdx = tlb_entry_most_recent;

        // Detach current
        int loc_prev = tlb_entries[idx][3];
        int loc_next = tlb_entries[idx][2];
        tlb_entries[loc_next][3] = loc_prev;
        tlb_entries[loc_prev][2] = loc_next;

        // Steal previous from most recent
        int prevloc_prev = tlb_entries[previdx][3];
        tlb_entries[idx][3] = prevloc_prev;

        // We are the new previous
        tlb_entries[previdx][3] = idx;

        // And the old recent is the new next
        tlb_entries[idx][2] = previdx;

        // And the old recent previous next is the new recent
        tlb_entries[prevloc_prev][2] = idx;

        // And, well, we are now the most recent
        tlb_entry_most_recent = idx;
    }

    public void reset()
    {
        this.hard_halted = false;
        this.pc = this.reset_pc;

        // reset TLB
        this.reset_tlb();

        // reset COP0 and whatnot
        this.c0_index = 0x00000000;
        this.c0_entrylo = 0x00000800;
        this.c0_context = 0x00000000;
        this.c0_vaddr = 0x00000000;
        this.c0_entryhi = 0x00000800;
        this.c0_cause = 0x00000000;
        this.c0_status = 0x00400000;
        this.c0_epc = 0x00000000;
        this.double_fault_detect = false;
        this.pf0_double_fault_detect = false;
        this.bd_detect = -1;

        // clear prefetch buffer
        this.pf0_pc = this.pc;
        this.pf0 = 0x00000000; // NOP
    }

    public Jipsy(PseudoVM vm, int ram_bytes_)
    {
        assert(ram_bytes_ >= 4);
        assert(ram_bytes_ <= (64<<20)); // cap at... is 64MB enough?

        // pad to 4-byte boundary
        ram_bytes_ = (ram_bytes_+3)&~3;

        this.vm = vm;
        this.ram_bytes = ram_bytes_;
        this.ram = new int[ram_bytes_>>2];
        this.regs = new int[32];
        this.cycles = 0;
        this.cycle_wait = 0;
        this.hard_halted = true;
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

    private void write_32_of(byte[] buf, int offs, int v)
    {
        if(offs+0 < buf.length) buf[offs+0] = (byte)(v>>0);
        if(offs+1 < buf.length) buf[offs+1] = (byte)(v>>8);
        if(offs+2 < buf.length) buf[offs+2] = (byte)(v>>16);
        if(offs+3 < buf.length) buf[offs+3] = (byte)(v>>24);
    }

    private void write_16_of(byte[] buf, int offs, short v)
    {
        if(offs+0 < buf.length) buf[offs+0] = (byte)(v>>0);
        if(offs+1 < buf.length) buf[offs+1] = (byte)(v>>8);
    }

    // FIXME: unaligned accesses are as per the ARM7TDMI in the GBA
    public int mem_read_32(int addr_)
    {
        if(addr_ >= 0x1FF00000) {
            if((addr_&~0x3F) == 0x1FF00200)
                return read_32_of(this.cmp_buf_addr, addr_&0x3F);
            if((addr_&~0x3F) == 0x1FF00240)
                return read_32_of(this.cmp_buf_type_method, addr_&0x3F);
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
                    Map<String, String> m = vm.machine.components();
                    int sz = m.size();
                    cmp_list_addr = new String[sz];
                    cmp_list_type = new String[sz];
                    int i = 0;
                    for (Map.Entry<String, String> n : m.entrySet()) {
                        cmp_list_addr[i] = n.getKey();
                        cmp_list_type[i] = n.getValue();
                        i++;
                    }
                    return cmp_list_addr.length;
                }

                case 0x00286: {
                    // method call argument count return
                    return cmp_call_retcnt;
                }

                case 0x00287: {
                    // event pull strobe
                    Signal sig = vm.machine.popSignal();
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

                    return retcount;
                }

                case 0x00288:
                    return this.cmp_call_retptr;

                case 0x0028C:
                    return this.cmp_call_retlen;

                default:
                    return 0;
            }
        }

        int v = this.ram[addr_>>>2];
        int b = (addr_ & 3);
        if(b == 0) return v;
        b <<= 3;
        int v0 = (v>>>b);
        int v1 = (v<<(32-b));
        return v0|v1;
    }

    public short mem_read_16(int addr_)
    {
        return (short)mem_read_32(addr_);
    }

    public byte mem_read_8(int addr_)
    {
        return (byte)mem_read_32(addr_);
    }

    public String mem_read_cstr(int addr_)
    {
        int len = 0;
        ByteArrayOutputStream bfp = new ByteArrayOutputStream();

        // cap it here
        while(len < 65536)
        {
            // stop if we aren't even in memory
            if(((addr_ + len) & 0x1FFFFFFF) >= this.ram_bytes)
                break;

            byte b = mem_read_8((addr_ + len) & 0x1FFFFFFF);
            this.cycles += 1;
            if(b == 0) break;
            bfp.write(0xFF&(int)b);
            len++;
        }

        //return bfp.toString("UTF-8");
        //return bfp.toString("ISO-8859-1");
        return bfp.toString();
    }

    public void mem_write_32_masked(int addr_, int data_, int mask_)
    {
        if((addr_&3) != 0)
        {
            System.out.printf("PC ~ %08X\n", this.pc - 8);
            throw new RuntimeException("misaligned 32-bit write");
        }

        if(addr_ >= 0x1FF00000) {
            if ((addr_ & ~0x3F) == 0x1FF00200) {
                if((addr_ & 0x3C) == 0x3C) mask_ &= 0x00FFFFFF;
                write_32_of_masked(this.cmp_buf_addr, addr_ & 0x3F, data_, mask_);
            } else if ((addr_ & ~0x3F) == 0x1FF00240) {
                if((addr_ & 0x3C) == 0x3C) mask_ &= 0x00FFFFFF;
                write_32_of_masked(this.cmp_buf_type_method, addr_ & 0x3F, data_, mask_);
            } else {
                System.out.printf("PC ~ %08X | %08X %08X %08X\n", this.pc - 8, addr_, data_, mask_);
                throw new RuntimeException("unhandled masked I/O write");
            }

            return;
        }

        this.ram[addr_ >>> 2] &= ~mask_;
        this.ram[addr_ >>> 2] |= data_ & mask_;
    }

    public void mem_write_32(int addr_, int data_)
    {
        if((addr_&3) != 0)
        {
            System.out.printf("PC ~ %08X\n", this.pc - 8);
            throw new RuntimeException("misaligned 32-bit write");
        }

        if ((addr_ & ~0x3F) == 0x1FF00200 && ((addr_ & 0x3F) != 0x3F))
            write_32_of(this.cmp_buf_addr, addr_ & 0x3F, data_);
        if ((addr_ & ~0x3F) == 0x1FF00240 && ((addr_ & 0x3F) != 0x3F))
            write_32_of(this.cmp_buf_type_method, addr_ & 0x3F, data_);

        if(addr_ >= 0x1FF00000) {
            if ((addr_ & ~0xFF) == 0x1FF00300)
                this.cmp_arg_typ_list[(addr_ >> 3) & 31][(addr_ >> 2) & 1] = data_;

            switch (addr_ & 0xFFFFF) {
                case 0x00020:
                    // sleep strobe
                    // TODO: get correct sleep time
                    this.need_sleep = true;
                    return;

                case 0x00280:
                    this.cmp_method_ptr = data_;
                    return;

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

        this.ram[addr_ >>> 2] = data_;
    }

    public void mem_write_16(int addr_, short data_)
    {
        if((addr_&1) != 0) throw new RuntimeException("misaligned 16-bit write");

        if ((addr_ & ~0x3F) == 0x1FF00200 && ((addr_ & 0x3F) != 0x3F))
            write_16_of(this.cmp_buf_addr, addr_ & 0x3F, data_);
        if ((addr_ & ~0x3F) == 0x1FF00240 && ((addr_ & 0x3F) != 0x3F))
            write_16_of(this.cmp_buf_type_method, addr_ & 0x3F, data_);

        if(addr_ >= 0x1FF00000)
            switch(addr_&0xFFFFF)
            {
                default:
                    return;
            }

        int b = (addr_>>1) & 1;
        int a = addr_ >>> 2;
        switch(b)
        {
            case 0:
                this.ram[a] = (this.ram[a] & 0xFFFF0000) | (((int)data_) & 0xFFFF);
                break;
            case 1:
                this.ram[a] = (this.ram[a] & 0x0000FFFF) | (((int)data_) << 16);
                break;
        }
    }

    public void mem_write_8(int addr_, byte data_)
    {
        if(addr_ >= 0x1FF00000) {
            if ((addr_ & ~0x3F) == 0x1FF00200 && ((addr_ & 0x3F) != 0x3F))
                this.cmp_buf_addr[addr_ & 0x3F] = data_;
            if ((addr_ & ~0x3F) == 0x1FF00240 && ((addr_ & 0x3F) != 0x3F))
                this.cmp_buf_type_method[addr_ & 0x3F] = data_;

            switch (addr_ & 0xFFFFF) {
                case 0x00004:
                    //System.out.printf("%c", data_);
                    this.outbuf += (char) data_;
                    if (this.outbuf.length() >= OUTBUF_LEN) {
                        // debug output disabled for now.
                        // might be useful for the analyser, though!
                        //System.out.print(this.outbuf);
                        this.outbuf = "";
                    }
                    return;

                case 0x00024:
                    c0_cause &= ~(1<<10);
                    long tnew = System.currentTimeMillis();
                    if ((tnew - interrupt_timer_next) >= 0) {
                        c0_cause |= (1 << 10);

                        interrupt_ready = true;
                        interrupt_timer_next += 1000 / 20;
                        //System.out.printf("timer interrupt refired - status=%08X, cause=%08X\n", c0_status, c0_cause);

                        // don't buffer too many ticks
                        if ((tnew - interrupt_timer_next) >= 2000) {
                            interrupt_timer_next = tnew + 1000 / 20;
                        }
                    }
                    return;

                case 0x00025:
                    if(data_ == 0) {
                        interrupt_timer_enabled = false;
                        c0_cause &= ~(1<<10);
                        //System.out.printf("timer interrupt stopped - status=%08X, cause=%08X\n", c0_status, c0_cause);
                    } else if(data_ == 1) {
                        interrupt_timer_enabled = true;
                        interrupt_timer_next = System.currentTimeMillis() + 1000 / 20;
                        //System.out.printf("timer interrupt started - status=%08X, cause=%08X\n", c0_status, c0_cause);
                    }
                    return;

                case 0x00284: {
                    // component search strobe

                    // ensure valid
                    if (cmp_list_addr == null || data_ < 0 || data_ >= cmp_list_addr.length) {
                        // it isn't
                        cmp_buf_addr[0] = 0;
                        cmp_buf_type_method[0] = 0;
                        return;
                    }

                    try {
                        byte[] b;
                        b = cmp_list_addr[data_].getBytes("ISO-8859-1");
                        System.arraycopy(b, 0, cmp_buf_addr, 0, Math.min(63, b.length));
                        cmp_buf_addr[Math.min(63, b.length)] = 0;
                        b = cmp_list_type[data_].getBytes("ISO-8859-1");
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
                    String method_name = mem_read_cstr(this.cmp_method_ptr & 0x1FFFFFFF);
                    //System.out.printf("Calling %s method %s...\n", address, method_name);
                    int arg_count = Math.min(32, data_);
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
                                args[i] = mem_read_cstr(this.cmp_arg_typ_list[i][0] & 0x1FFFFFFF);
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
                            rets = vm.machine.invoke(address, method_name, args);
                        } catch(LimitReachedException e) {
                            // jump back to here
                            // FIXME: BLATANT HACK
                            //System.err.printf("%08X: limit reached: %s -> %s.\n", this.op_pc, address, method_name);
                            this.pc = this.op_pc;
                            this.pf0_pc = this.pc;
                            this.pf0 = 0x00000000; // NOP
                            this.need_sleep = true;
                            this.sync_call = true;
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
                    if(this.sync_call) {
                        this.need_sleep = true;
                        this.sync_call = false;
                    }
                    return;
                }

                case 0x00287: {
                    // fetch string strobe

                    // ignore if invalid
                    if(data_ < 0 || data_ >= this.cmp_call_retcnt
                            || this.cmp_call_retarray[data_] == null)
                        return;

                    for(int i = 0; i < Math.min(this.cmp_call_retlen,
                            this.cmp_call_retarray[data_].length); i++)
                    {
                        // lop off top few bits for convenience

                        // stop if we aren't even in memory
                        if(((this.cmp_call_retptr+i) & 0x1FFFFFFF) >= this.ram_bytes)
                            break;

                        // write
                        this.mem_write_8((this.cmp_call_retptr+i) & 0x1FFFFFFF, this.cmp_call_retarray[data_][i]);
                        this.cycles += 1;
                    }

                    return;
                }

                default:
                    return;
            }
        }

        int b = addr_ & 3;
        int a = addr_ >>> 2;
        b <<= 3;
        int v = ((int)data_) & 0xFF;
        int m = 0xFF << b;

        this.ram[a] = (this.ram[a] & ~m) | (v << b);
    }

    protected void swi(int op_pc, int op)
    {
        // TODO!
    }

    private int remap_tlb(int addr_, boolean is_write)
    {
        // TEST: disable TLB?
        if(false) return addr_&0x7FFFFFFF;

        // Expected vaddr
        int expect_ehi = (addr_&~0xFFF)|(c0_entryhi&0xFC0);

        // Read TLB
        // FIXME: need to fire TLB exception instead of UTLB exception if matching but not valid
        for(int i = 0, idx = tlb_entry_most_recent; i < 64; i++, idx = tlb_entries[idx][2]) {
            // Fetch TLB entry
            int elo = tlb_entries[idx][0]; // physical + flags
            int ehi = tlb_entries[idx][1]; // virtual  + ASID

            // Check if valid
            if((elo & 0x200) != 0) {

                // Check if global
                if ((elo & 0x100) != 0)
                    ehi = (ehi & ~0xFC0) | (c0_entryhi & 0xFC0);

                // Check if vaddr in range
                if (ehi == expect_ehi) {
                    // This is our TLB!

                    // Check dirty flag
                    if(is_write && (elo & 0x400) == 0) {
                        // Cannot write to this page!
                        c0_vaddr = addr_;
                        return -1-EX_Mod;
                    }

                    touch_tlb(idx);
                    return (addr_&0xFFF)|(elo&0x7FFFF000);
                }
            }

            // Punish anyone who wants to be a dick with the TLB
            if((i&3) == 3) this.cycles += 1;
        }

        // TLB fault!
        c0_vaddr = addr_;
        return -1-EX_TLBL;
    }

    private int remap_address_main(int addr_, boolean is_write)
    {
        if((addr_ & 0x80000000) == 0) {
            return remap_tlb(addr_, is_write);

        } else if((c0_status & (0x02)) != 0) {
            // user mode does NOT have access to here
            c0_vaddr = addr_;
            return -1-EX_AdEL;

        } else {
            switch((addr_>>>29)&3)
            {
                default:
                case 0: // kernel unmapped
                    return addr_&0x1FFFFFFF;
                case 1: // kernel unmapped uncached
                    return addr_&0x1FFFFFFF;
                case 2: // kernel mapped
                case 3:
                    return remap_tlb(addr_, is_write);
            }
        }
    }

    private int remap_address_phys(int addr_, boolean is_write) {
        // check if in I/O space
        if ((addr_ & 0xFFF00000) == 0x1FF00000)
            return addr_;

        // do some basic RAM remaps
        if ((addr_ & 0xFFFFF000) == 0x1FC00000)
            addr_ = (addr_ & 0xFFF) | 0x00000000;

        // check if in RAM range
        if (addr_ < this.ram_bytes)
            return addr_;

        // fault!
        return -1 - EX_DBE;
    }

    private int remap_address(int addr_, boolean is_write)
    {
        // get address
        addr_ = remap_address_main(addr_, is_write);

        // check if faulted
        if(addr_ < 0)
            return addr_;

        // return physical remapping
        return remap_address_phys(addr_, is_write);
    }

    private void isr(int fault, int ce)
    {
        this.pf0_fault = -1;

        if(this.double_fault_detect)
            throw new RuntimeException(String.format("2xfault 0x%02X @ %08X"
                    ,(this.c0_cause>>2)&15
                    , this.c0_epc));

        this.double_fault_detect = true;
        this.pf0_double_fault_detect = true;

        int bd = (this.bd_detect==this.op_pc?1:0);

        this.c0_cause = (this.c0_cause & ~0xB000007C)
                | ((fault&31)<<2)
                | ((bd&1)<<31)
                | ((ce&3)<<28)
                ;

        this.c0_epc = this.op_pc - (bd<<2);
        c0_status = (c0_status & ~0x3F)
                | ((c0_status<<2) & 0x3C)
                | 0x0
                ;

        c0_context = (c0_context & 0xFF800000) | ((c0_vaddr>>>10) & 0x0007FFFC);
        c0_entryhi = (c0_entryhi & 0x00000FFF) | (c0_vaddr & 0xFFFFF000);

        boolean is_utlb_miss = (
                (fault == EX_TLBL || fault == EX_TLBS)
                && (c0_vaddr & 0x80000000) == 0);

        this.pc = (((this.c0_status&(1<<22)) != 0)
            ? 0xBFC00100
            : 0x80000000) + (is_utlb_miss ? 0x00 : 0x80);
        this.pf0_pc = this.pc;
        this.pf0 = 0x00000000; // NOP

        if(false) {
            // Fault debugging message
            System.err.printf("FAULT: C=%08X SR=%08X EPC=%08X PC=%08X\n"
                    , this.c0_cause, this.c0_status, this.c0_epc, this.pc);
            //if(bd != 0) System.err.printf("BRANCH DELAY FAULT\n");
            this.need_sleep = true; // Don't spam the console
        }

        this.bd_detect = -1;
    }

    public synchronized void run_op()
    {
        // Handle fault when ready
        if(pf0_fault != -1) {
            this.op_pc = pf0_fault_pc;
            this.isr(pf0_fault, 0);
            return;
        }

        // Update PC address
        int op = this.pf0;
        int pc = this.pf0_pc;
        this.op_pc = pc;

        // Check if interrupt ready
        if(interrupt_ready) {
            interrupt_ready = false;

            // Check validity of request
            if((c0_status & 0x01) != 0) {
                if((c0_status & c0_cause & 0x0000FF00) != 0) {
                    // Fire interrupt!
                    this.isr(EX_Int, 0);
                    return;
                }
            }
        }

        // Get expected PC address
        int phys_pc = this.remap_address(this.pc, false);

        if(phys_pc < 0) {
            // Delegate to next op
            int fault = -phys_pc-1;
            if(fault == EX_DBE) fault = EX_IBE;
            this.pf0_fault = fault;
            this.pf0_fault_pc = this.pc;
        } else {
            // FIXME: clear bd_detect once branched
            // here's a case where it'll break:
            //     b L0
            // L0: addiu $v0, $v0, 1
            //
            // if an interrupt fires on the second execution of addiu and cancels the op,
            // it will jump back to the branch and add 3 total,
            // instead of the expected 2.
            //
            // another case is if you manage to somehow wrap the whole entire address space
            // and remove the branch,
            // but thankfully that's not actually possible.

            // Fetch
            int new_op = this.mem_read_32(phys_pc);
            this.pf0 = new_op;
            this.pf0_pc = this.pc;
            this.pc += 4;
        }

        this.cycles += 1;

        //System.err.printf("PC = %08X -> %08X - op = %08X", this.pc, phys_pc, new_op);

        // Detect double fault
        boolean next_double_fault = this.pf0_double_fault_detect;
        this.pf0_double_fault_detect = false;

        int otyp0 = (op>>>26);
        int rs = (op>>>21)&0x1F;
        int rt = (op>>>16)&0x1F;
        int rd = (op>>>11)&0x1F;
        int sh = (op>>>6)&0x1F;
        int otyp1 = (op)&0x3F;

        // we're pretending that the load/store delay slot isn't a thing
        // the reason for this is it's unreliable especially in the context of interrupts
        // thus i'm pretty sure compilers avoid it
        //System.out.printf("%08X: %08X\n", pc, op);

        int tmp0, tmp1;

        if(otyp0 == 0x00) switch(otyp1) {

            case 0x00: // SLL
                if(rd != 0) this.regs[rd] = this.regs[rt] << sh;
                break;
            case 0x02: // SRL
                if(rd != 0) this.regs[rd] = this.regs[rt] >>> sh;
                break;
            case 0x03: // SRA
                if(rd != 0) this.regs[rd] = this.regs[rt] >> sh;
                break;

            case 0x04: // SLLV
                if(rd != 0) this.regs[rd] = this.regs[rt] << (this.regs[rs] & 0x1F);
                break;
            case 0x06: // SRLV
                if(rd != 0) this.regs[rd] = this.regs[rt] >>> (this.regs[rs] & 0x1F);
                break;
            case 0x07: // SRAV
                if(rd != 0) this.regs[rd] = this.regs[rt] >> (this.regs[rs] & 0x1F);
                break;

            case 0x09: // JALR
                this.regs[rd] = pc+8;
            case 0x08: // JR
                this.pc = this.regs[rs];
                this.bd_detect = this.op_pc+4;
                break;

            case 0x0C: // SYSCALL
                this.isr(EX_Sys, 0);
                return;
            case 0x0D: // BREAK
                this.isr(EX_Bp, 0);
                return;

            // XXX: do we pipeline lo/hi and introduce delays?
            case 0x10: // MFHI
                if(rd != 0) this.regs[rd] = this.rhi;
                break;
            case 0x11: // MTHI
                this.rhi = this.regs[rd];
                break;
            case 0x12: // MFLO
                if(rd != 0) this.regs[rd] = this.rlo;
                break;
            case 0x13: // MTLO
                this.rlo = this.regs[rs];
                break;

            case 0x18: // MULT
                {
                    long va = (long)this.regs[rs];
                    long vb = (long)this.regs[rt];
                    long result = va*vb;
                    this.rlo = (int)result;
                    this.rhi = (int)(result>>32);

                    if(va >= -0x800 && va < 0x800)
                        this.cycles += 6-1;
                    else if(va >= -0x100000 && va < 0x100000)
                        this.cycles += 9-1;
                    else
                        this.cycles += 13-1;
                }
                break;
            case 0x19: // MULTU
                {
                    long va = 0xFFFFFFFFL&(long)this.regs[rs];
                    long vb = 0xFFFFFFFFL&(long)this.regs[rt];
                    long result = va*vb;
                    this.rlo = (int)result;
                    this.rhi = (int)(result>>32);

                    if(va >= 0 && va < 0x800)
                        this.cycles += 6-1;
                    else if(va >= 0 && va < 0x100000)
                        this.cycles += 9-1;
                    else
                        this.cycles += 13-1;
                }
                break;
            case 0x1A: // DIV
                if(this.regs[rt] == 0)
                {
                    if(this.regs[rs] >= 0)
                        this.rlo = -1;
                    else
                        this.rlo = 1;

                    this.rhi = this.regs[rs];

                } else {
                    long vnum = 0xFFFFFFFFL&(long)this.regs[rs];
                    long vdenom = 0xFFFFFFFFL&(long)this.regs[rt];

                    // TODO: figure out % behaviour when negative
                    this.rlo = (int)(vnum / vdenom);
                    this.rhi = (int)(vnum % vdenom);
                }

                this.cycles += 36-1;
                break;
            case 0x1B: // DIVU
                if(this.regs[rt] == 0)
                {
                    this.rlo = 0xFFFFFFFF;
                    this.rhi = this.regs[rs];

                } else {
                    long vnum = 0xFFFFFFFFL&(long)this.regs[rs];
                    long vdenom = 0xFFFFFFFFL&(long)this.regs[rt];

                    this.rlo = (int)(vnum / vdenom);
                    this.rhi = (int)(vnum % vdenom);
                }

                //System.out.printf("DIVU %d %d -> %d %d\n", this.regs[rs], this.regs[rt], this.rlo, this.rhi);
                this.cycles += 36-1;
                break;

            case 0x20: // ADD
                tmp0 = this.regs[rs] + this.regs[rt];
                if((this.regs[rs] <= 0) == (this.regs[rt] <= 0)
                        && (tmp0 <= 0) != (this.regs[rs] <= 0)) {

                    this.isr(EX_Ov, 0);
                    return;
                } else {
                    if (rd != 0) this.regs[rd] = tmp0;
                }
                break;

            case 0x21: // ADDU
                if(rd != 0) this.regs[rd] = this.regs[rs] + this.regs[rt];
                break;

            case 0x22: // SUB
                tmp0 = this.regs[rs] - this.regs[rt];
                if((this.regs[rs] <= 0) == (this.regs[rt] >= 0)
                        && (tmp0 <= 0) != (this.regs[rs] <= 0)) {

                    this.isr(EX_Ov, 0);
                    return;
                } else {
                    if (rd != 0) this.regs[rd] = tmp0;
                }
                break;

            case 0x23: // SUBU
                if(rd != 0) this.regs[rd] = this.regs[rs] - this.regs[rt];
                break;

            case 0x24: // AND
                if(rd != 0) this.regs[rd] = this.regs[rs] & this.regs[rt];
                break;
            case 0x25: // OR
                if(rd != 0) this.regs[rd] = this.regs[rs] | this.regs[rt];
                break;
            case 0x26: // XOR
                if(rd != 0) this.regs[rd] = this.regs[rs] ^ this.regs[rt];
                break;
            case 0x27: // NOR
                if(rd != 0) this.regs[rd] = ~(this.regs[rs] | this.regs[rt]);
                break;

            case 0x2A: // SLT
                if(rd != 0) this.regs[rd] =
                    (this.regs[rs] < this.regs[rt] ? 1 : 0);
                break;
            case 0x2B: // SLTU
                if(rd != 0) this.regs[rd] =
                    (this.regs[rs]+0x80000000 < this.regs[rt]+0x80000000 ? 1 : 0);
                break;

            default:
                System.out.printf("%08X: %08X %02X\n", pc, op, otyp1);
                {
                    this.isr(EX_RI, 0);
                    return;
                }

        } else if(otyp0 == 0x01) switch(rt) {

            case 0x00: // BLTZ
                // XXX: I don't know if this is set regardless of branch
                // Either way, both behaviours are plausible
                // In this case we're going with "set only if taken"
                if(this.regs[rs] <  0) {
                    this.pc = pc + 4 + (((int) (short) op) << 2);
                    this.bd_detect = this.op_pc+4;
                }
                break;
            case 0x01: // BGEZ
                if(this.regs[rs] >= 0) {
                    this.pc = pc + 4 + (((int) (short) op) << 2);
                    this.bd_detect = this.op_pc+4;
                }
                break;

            case 0x10: // BLTZAL
                if(this.regs[rs] <  0) {
                    this.regs[31] = pc + 8;
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                    this.bd_detect = this.op_pc+4;
                }
                break;
            case 0x11: // BGEZAL
                if(this.regs[rs] >= 0) {
                    this.regs[31] = pc + 8;
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                    this.bd_detect = this.op_pc+4;
                }
                break;
            default:
                System.out.printf("%08X: %08X %02X\n", pc, op, rt);
                {
                    this.isr(EX_RI, 0);
                    return;
                }

        } else switch(otyp0) {

            case 0x03: // JAL
                this.regs[31] = pc + 8;
            case 0x02: // J
                this.pc = (pc & 0xF0000000)|((op&((1<<26)-1))<<2);
                this.bd_detect = this.op_pc+4;
                break;

            case 0x04: // BEQ
                if(this.regs[rs] == this.regs[rt]) {
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                    this.bd_detect = this.op_pc+4;
                }
                break;
            case 0x05: // BNE
                if(this.regs[rs] != this.regs[rt]) {
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                    this.bd_detect = this.op_pc+4;
                }
                break;
            case 0x06: // BLEZ
                if(this.regs[rs] <= 0) {
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                    this.bd_detect = this.op_pc+4;
                }
                break;
            case 0x07: // BGTZ
                if(this.regs[rs] >  0) {
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                    this.bd_detect = this.op_pc+4;
                }
                break;

            case 0x08: // ADDI
                tmp1 = (int)(short)op;
                tmp0 = this.regs[rs] + tmp1;
                if((this.regs[rs] <= 0) == (tmp1 <= 0)
                        && (tmp0 <= 0) != (this.regs[rs] <= 0)) {

                    this.isr(EX_Ov, 0);
                    return;
                } else {
                    if (rt != 0) this.regs[rt] = tmp0;
                }
                break;

            case 0x09: // ADDIU
                if(rt != 0) this.regs[rt] = this.regs[rs] + (int)(short)op;
                break;

            case 0x0A: // SLTI
                if(rt != 0) this.regs[rt] =
                    (this.regs[rs] < (int)(short)op ? 1 : 0);
                break;
            case 0x0B: // SLTIU
                if(rt != 0) this.regs[rt] =
                    (this.regs[rs]+0x80000000 < ((int)(short)op)+0x80000000 ? 1 : 0);
                break;

            case 0x0C: // ANDI
                if(rt != 0) this.regs[rt] = this.regs[rs] & (op&0xFFFF);
                break;
            case 0x0D: // ORI
                if(rt != 0) this.regs[rt] = this.regs[rs] | (op&0xFFFF);
                break;
            case 0x0E: // XORI
                if(rt != 0) this.regs[rt] = this.regs[rs] ^ (op&0xFFFF);
                break;
            case 0x0F: // LUI
                if(rt != 0) this.regs[rt] = (op&0xFFFF)<<16;
                break;

            case 0x10: // COP0
            case 0x11: // COP1
            case 0x12: // COP2
            case 0x13: // COP3
                // Check if coprocessor enabled
                if(((otyp0&3) != 0 || (c0_status&0x02) != 0)
                        && 0 == (c0_status&(1<<(28+(otyp0&3))))) { // LISP poster-child in Java
                    this.isr(EX_CpU, otyp0&3);
                    return;
                }

                // No, these aren't supported either
                if((otyp0&3) > 1) {
                    this.isr(EX_CpU, otyp0&3);
                    return;
                }

                if((otyp0&3) == 0) {
                    // Check op type
                    if (rs >= 16) switch (otyp1) {
                        case 0x01: {
                            // TLBR
                            c0_entrylo = tlb_entries[(c0_index >> 8) & 63][0];
                            c0_entryhi = tlb_entries[(c0_index >> 8) & 63][1];
                        }
                        break;

                        case 0x02: {
                            // TLBWI
                            // TODO: detect TLB match and set TLB Shutdown flag
                            // allegedly this is optional but potentially dangerous without it
                            // MIPS32 also defines a machine check exception @ 0x18,
                            // but this is out of range.
                            tlb_entries[(c0_index >> 8) & 63][0] = c0_entrylo;
                            tlb_entries[(c0_index >> 8) & 63][1] = c0_entryhi;
                            touch_tlb((c0_index >> 8) & 63);
                        }
                        break;

                        case 0x06: {
                            // TLBWR
                            // use least recently touched one
                            // TODO: detect TLB match prior to adding this in
                            int i, idx;
                            for (i = 0, idx = tlb_entries[tlb_entry_most_recent][3]; i < 64; i++) {
                                // is this TLB unwired?
                                if (idx >= 8) {
                                    // touch and go
                                    tlb_entries[idx][0] = c0_entrylo;
                                    tlb_entries[idx][1] = c0_entryhi;
                                    //System.out.printf("TLBWR: %02X %08X <- %08X\n", idx, c0_entrylo, c0_entryhi);
                                    touch_tlb(idx);
                                    break;
                                }

                                // step back
                                idx = tlb_entries[idx][3];
                                this.cycles += 1;
                            }

                            if (i >= 64) {
                                // well, shit.
                                throw new RuntimeException("BUG: TLB chain has no unwired pages?!");
                            }
                        }
                        break;

                        case 0x08: {
                            // TLBP
                            int fault = remap_tlb(c0_entryhi, false);
                            if (fault >= 0) {
                                c0_index = this.tlb_entry_most_recent << 8;
                            } else {
                                c0_index = 0x80000000;
                            }
                        }
                        break;

                        case 0x10: // RFE
                            c0_status = (c0_status & ~0x0F) | ((c0_status >> 2) & 0x0F);
                            interrupt_ready = true;
                            break;

                        default:
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                            return;

                    }
                    else switch (rs) {
                        case 0: // MFCn
                            switch (rd) {
                                case 0: // TLB index
                                    tmp0 = c0_index;
                                    break;
                                case 1: // TLB random index
                                    tmp0 = ((int) (Math.random() * (64 - 8) + 8)) << 8;
                                    break;
                                case 2: // TLB entrylo (phys + flags)
                                    tmp0 = c0_entrylo;
                                    break;
                                case 4: // Context + bad virtual address
                                    tmp0 = c0_context;
                                    break;
                                case 8: // Bad virtual address
                                    tmp0 = c0_vaddr;
                                    break;
                                case 10: // TLB entryhi (virt + ASID)
                                    tmp0 = c0_entryhi;
                                    break;
                                case 12: // SR
                                    tmp0 = c0_status;
                                    break;
                                case 13: // Cause
                                    tmp0 = c0_cause;
                                    break;
                                case 14: // EPC
                                    tmp0 = c0_epc;
                                    break;
                                case 15: // PRID
                                    //tmp0 = 0x00000300;
                                    tmp0 = 0x00000100; // pretend to be an R2000
                                    break;
                                default:
                                    System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                                    this.isr(EX_RI, 0);
                                    return;
                            }
                            if (rt != 0)
                                this.regs[rt] = tmp0;
                            break;
                        case 2: // CFCn
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0);
                            return;
                        case 4: // MTCn
                            tmp0 = this.regs[rt];
                            switch (rd) {
                                case 0: // TLB index
                                    tmp1 = 0x00003F00;
                                    c0_index = (c0_index & ~tmp1) | (tmp0 & tmp1);
                                    break;
                                case 2: // TLB entrylo (phys + flags)
                                    tmp1 = 0xFFFFFF00;
                                    c0_entrylo = (c0_entrylo & ~tmp1) | (tmp0 & tmp1);
                                    break;
                                case 4: // Context + bad virtual address
                                    tmp1 = 0xFF800000;
                                    c0_context = (c0_context & ~tmp1) | (tmp0 & tmp1);
                                    break;
                                case 10: // TLB entryhi (virt + ASID)
                                    tmp1 = 0xFFFFFFC0;
                                    c0_entryhi = (c0_entryhi & ~tmp1) | (tmp0 & tmp1);
                                    break;
                                case 12: // Status
                                    tmp1 = 0x3040FF3F;
                                    c0_status = (c0_status & ~tmp1) | (tmp0 & tmp1);
                                    interrupt_ready = true;
                                    break;

                                case 13: // Cause
                                    // You can write to the software interrupt bits.
                                    // This requests an interrupt.
                                    tmp1 = 0x00000300;
                                    c0_cause = (c0_cause & ~tmp1) | (tmp0 & tmp1);
                                    interrupt_ready = true;
                                    break;

                                case 14: // EPC
                                    // Apparently you can write anything to this
                                    c0_epc = tmp0;
                                    break;

                                default:
                                    System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                                    this.isr(EX_RI, 0);
                                    return;
                            }
                            break;

                        case 6: // CTCn
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0);
                            return;
                        default:
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                            return;
                    }

                } else if((otyp0&3) == 1) {
                    // COP1
                    // Check op type
                    if (rs >= 16) switch (otyp1) {

                        default:
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                            return;

                    }

                    else switch (rs) {
                        case 0: // MFCn
                            switch (rd) {
                                case 123:
                                    tmp0 = 0;
                                    break;
                                default:
                                    System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                                    this.isr(EX_RI, 0);
                                    return;
                            }
                            if (rt != 0)
                                this.regs[rt] = tmp0;
                            break;
                        case 2: // CFCn
                            switch (rd) {
                                case 0: // FCR0
                                    tmp0 = 0; // we have no FPU
                                    break;
                                default:
                                    System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                                    this.isr(EX_RI, 0);
                                    return;
                            }
                            if (rt != 0)
                                this.regs[rt] = tmp0;
                            break;
                        case 4: // MTCn
                            tmp0 = this.regs[rt];
                            switch (rd) {
                                case 123:
                                    break;
                                default:
                                    System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                                    this.isr(EX_RI, 0);
                                    return;
                            }
                            break;

                        case 6: // CTCn
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0);
                            return;
                        default:
                            System.out.printf("%08X: %08X %02X\n", pc, op, rs);
                            this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                            return;
                    }

                }
                break;

            case 0x20: // LB
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = (int)this.mem_read_8(tmp1);
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x21: // LH
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 >= 0 && (tmp1&1) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = (int)this.mem_read_16(tmp1);
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x23: // LW
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 >= 0 && (tmp1&3) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.mem_read_32(tmp1);
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x24: // LBU
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = 0xFF&((int)(this.mem_read_8(tmp1)));
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x25: // LHU
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 >= 0 && (tmp1&1) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = 0xFFFF&((int)(this.mem_read_16(tmp1)));
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;

            case 0x28: // SB
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                this.mem_write_8(tmp1, (byte)this.regs[rt]);
                this.cycles += 1;
                break;
            case 0x29: // SH
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                if(tmp1 >= 0 && (tmp1&1) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                this.mem_write_16(tmp1, (short)this.regs[rt]);
                this.cycles += 1;
                break;
            case 0x2B: // SW
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                if(tmp1 >= 0 && (tmp1&3) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                this.mem_write_32(tmp1, this.regs[rt]);
                this.cycles += 1;
                break;

            // Keeping this section separate to ensure sanity.
            //
            // Repeat after me: 4,814,976
            // Also repeat after me: 2006-12-23
            // And one more: Thank you for the early Christmas present
            //
            // TODO: abuse pipeline bypass
            case 0x22: // LWL
                tmp1 = this.remap_address(this.regs[rs] + (int)(short)op, false);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.mem_read_32(tmp1&~3);
                tmp1 ^= 3;
                this.cycles += 1;
                if(rt != 0) this.regs[rt] = (this.regs[rt] & ~(0xFFFFFFFF<<((tmp1&3)<<3)))
                    | (tmp0<<((tmp1&3)<<3));
                //System.out.printf("LWL %08X %d\n", this.regs[rt], tmp1&3);
                break;
            case 0x26: // LWR
                tmp1 = this.remap_address(this.regs[rs] + (int)(short)op, false);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.mem_read_32(tmp1&~3);
                this.cycles += 1;
                if(rt != 0) this.regs[rt] = (this.regs[rt] & ~(0xFFFFFFFF>>>((tmp1&3)<<3))
                    | (tmp0>>>((tmp1&3)<<3)));
                //System.out.printf("LWR %08X %d\n", this.regs[rt], tmp1&3);
                break;

            // Note from psx-spx:
            //
            // The CPU has four separate byte-access signals, so, within a 32bit location,
            // it can transfer all fragments of Rt at once (including for odd 24bit amounts).
            // ^ this is the critical point
            //
            // The transferred data is not zero- or sign-expanded,
            // eg. when transferring 8bit data,
            // the other 24bit of Rt and [mem] will remain intact.
            // ^ this is the not so critical point as it's almost obvious
            case 0x2A: // SWL
                tmp1 = this.remap_address(this.regs[rs] + (int)(short)op, true);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.regs[rt];
                tmp1 ^= 3;
                //System.out.printf("SWL %d\n", tmp1&3);
                this.mem_write_32_masked(tmp1&~3, tmp0>>>((tmp1&3)<<3),
                    0xFFFFFFFF>>>((tmp1&3)<<3));
                this.cycles += 1;
                break;
            case 0x2E: // SWR
                tmp1 = this.remap_address(this.regs[rs] + (int)(short)op, true);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.regs[rt];
                //System.out.printf("SWR %d\n", tmp1&3);
                this.mem_write_32_masked(tmp1&~3, tmp0<<((tmp1&3)<<3),
                    0xFFFFFFFF<<((tmp1&3)<<3));
                this.cycles += 1;
                break;

            default:
                System.out.printf("%08X: %08X %02X\n", pc, op, otyp0);
                this.isr(EX_RI, 0);
        }

        this.double_fault_detect = next_double_fault;
    }

    public synchronized void run_cycles(int ccount)
    {
        assert(ccount > 0);
        assert(ccount < 0x40000000);

        this.outbuf = "";
        int cyc_end = this.cycles + ccount - this.cycle_wait;

        if(interrupt_timer_enabled) {
            long tnew = System.currentTimeMillis();
            if ((tnew - interrupt_timer_next) >= 0) {
                c0_cause |= (1 << 10);

                interrupt_timer_next += 1000 / 20;
                interrupt_ready = true;
                //System.out.printf("timer interrupt fired - status=%08X, cause=%08X\n", c0_status, c0_cause);

                // don't buffer too many ticks
                if ((tnew - interrupt_timer_next) >= 2000) {
                    interrupt_timer_next = tnew + 1000 / 20;
                }
            }
        }

        this.need_sleep = false;
        while(!this.hard_halted && !this.need_sleep && (cyc_end - this.cycles) >= 0)
        {
            int pc = this.pc;

            try {
                this.run_op();
            } catch (RuntimeException e) {
                System.err.printf("Exception in MIPS emu - halted!\n");
                this.vm.bsod(e.getMessage());
                e.printStackTrace();
                this.hard_halted = true;
            }
        }

        if(this.outbuf.length() > 0)
        {
            //System.out.print(this.outbuf);
            this.outbuf = "";
        }

        this.cycle_wait = Math.max(0, this.cycles - cyc_end);
    }

    public static short bswap16(short v)
    {
        v = (short)(((v>>>8)&0xFF)|(v<<8));
        return v;
    }

    public static int bswap32(int v)
    {
        v = (v>>>16)|(v<<16);
        v = ((v&0xFF00FF00)>>>8)|((v&0x00FF00FF)<<8);

        return v;
    }
}

