package potato.chocolate.mods.ebola.arch.mips;

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
    private int reset_pc = 0x00001000;
    boolean hard_halted = false;
    boolean need_sleep = false;

    private PseudoVM vm;
    static final int OUTBUF_LEN = 1;

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

    int cmp_call_retcnt = 0;
    byte[][] cmp_call_retarray = new byte[32][];
    int cmp_call_retptr = 0;
    int cmp_call_retlen = 0;

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

    public void set_reset_pc(int pc)
    {
        this.reset_pc = pc;
    }

    public void reset()
    {
        this.hard_halted = false;
        this.pc = this.reset_pc;
        this.pf0_pc = this.pc;
        this.pf0 = this.mem_read_32(this.pc);
        this.pc += 4;
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
        this.reset();
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
                    // TODO: debug port input
                    return 0xFF&(int)'e';
                    //return -3;
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
            byte b = mem_read_8(addr_ + len);
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
                        System.out.print(this.outbuf);
                        this.outbuf = "";
                    }
                    return;

                case 0x00020:
                    // sleep strobe
                    // TODO: get correct sleep time
                    this.need_sleep = true;
                    break;

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
                    String method_name = mem_read_cstr(this.cmp_method_ptr);
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
                                args[i] = mem_read_cstr(this.cmp_arg_typ_list[i][0]);
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
                        Object[] rets = vm.machine.invoke(address, method_name, args);
                        this.cmp_call_retcnt = (rets == null ? 0 : rets.length);
                        for (int i = 0; i < this.cmp_call_retcnt; i++) {
                            parse_retval(i, rets[i]);
                        }
                    } catch(Exception e) {
                        System.err.printf("exception!\n");
                        e.printStackTrace();
                        String err = e.getMessage();
                        byte[] b = err.getBytes();
                        System.arraycopy(b, 0, this.cmp_buf_error, 0, Math.min(63, b.length));
                        this.cmp_buf_error[Math.min(63, b.length)] = 0;
                        this.cmp_buf_error[63] = 0;
                        this.cmp_call_retcnt = -1;
                    }
                    // force a sleep
                    this.need_sleep = true;
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
                        this.mem_write_8(this.cmp_call_retptr+i, this.cmp_call_retarray[data_][i]);
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

    public void run_op()
    {
        // Fetch
        int op = this.pf0;
        int pc = this.pf0_pc;
        int new_op = this.mem_read_32(this.pc);
        this.pf0 = new_op;
        this.pf0_pc = this.pc;
        this.pc += 4;
        this.cycles += 1;

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
                break;

            // XXX: do we pipeline lo/hi and introduce delays?
            case 0x10: // MFHI
                if(rd != 0) this.regs[rd] = this.rhi;
                break;
            case 0x12: // MFLO
                if(rd != 0) this.regs[rd] = this.rlo;
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
            case 0x21: // ADDU
                if(rd != 0) this.regs[rd] = this.regs[rs] + this.regs[rt];
                break;

            case 0x22: // SUB
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
                throw new RuntimeException("unsupported SPECIAL op");

        } else if(otyp0 == 0x01) switch(rt) {

            case 0x00: // BLTZ
                if(this.regs[rs] <  0)
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                break;
            case 0x01: // BGEZ
                if(this.regs[rs] >= 0)
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                break;

            case 0x10: // BLTZAL
                if(this.regs[rs] <  0)
                {
                    this.regs[31] = pc + 8;
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                }
                break;
            case 0x11: // BGEZAL
                if(this.regs[rs] >= 0)
                {
                    this.regs[31] = pc + 8;
                    this.pc = pc + 4 + (((int)(short)op)<<2);
                }
                break;
            default:
                System.out.printf("%08X: %08X %02X\n", pc, op, rt);
                throw new RuntimeException("unsupported BRANCH op");
        
        } else switch(otyp0) {

            case 0x03: // JAL
                this.regs[31] = pc + 8;
            case 0x02: // J
                this.pc = (pc & 0xF0000000)|((op&((1<<26)-1))<<2);
                break;

            case 0x04: // BEQ
                if(this.regs[rs] == this.regs[rt]) this.pc = pc + 4 + (((int)(short)op)<<2);
                break;
            case 0x05: // BNE
                if(this.regs[rs] != this.regs[rt]) this.pc = pc + 4 + (((int)(short)op)<<2);
                break;
            case 0x06: // BLEZ
                if(this.regs[rs] <= 0) this.pc = pc + 4 + (((int)(short)op)<<2);
                break;
            case 0x07: // BGTZ
                if(this.regs[rs] >  0) this.pc = pc + 4 + (((int)(short)op)<<2);
                break;

            // TODO: trap on non-U arithmetic ops
            case 0x08: // ADDI
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

            /*
            case 0x10: // COP0
                // TODO!
                System.out.printf("%08X: COP0 %02X\n", pc, op);
                break;
            */

            case 0x20: // LB
                tmp0 = (int)this.mem_read_8(this.regs[rs] + (int)(short)op);
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x21: // LH
                tmp0 = (int)this.mem_read_16(this.regs[rs] + (int)(short)op);
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x23: // LW
                tmp0 = this.mem_read_32(this.regs[rs] + (int)(short)op);
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x24: // LBU
                tmp0 = 0xFF&((int)(this.mem_read_8(this.regs[rs] + (int)(short)op)));
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x25: // LHU
                tmp0 = 0xFFFF&((int)(this.mem_read_16(this.regs[rs] + (int)(short)op)));
                if(rt != 0) this.regs[rt] = tmp0;
                this.cycles += 1;
                break;

            case 0x28: // SB
                this.mem_write_8(this.regs[rs] + (int)(short)op, (byte)this.regs[rt]);
                this.cycles += 1;
                break;
            case 0x29: // SH
                //System.out.printf("%08X %08X\n", this.regs[rs], (int)(short)op);
                this.mem_write_16(this.regs[rs] + (int)(short)op, (short)this.regs[rt]);
                this.cycles += 1;
                break;
            case 0x2B: // SW
                this.mem_write_32(this.regs[rs] + (int)(short)op, this.regs[rt]);
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
                tmp1 = this.regs[rs] + (int)(short)op;
                tmp0 = this.mem_read_32(tmp1&~3);
                tmp1 ^= 3;
                this.cycles += 1;
                if(rt != 0) this.regs[rt] = (this.regs[rt] & ~(0xFFFFFFFF<<((tmp1&3)<<3)))
                    | (tmp0<<((tmp1&3)<<3));
                //System.out.printf("LWL %08X %d\n", this.regs[rt], tmp1&3);
                break;
            case 0x26: // LWR
                tmp1 = this.regs[rs] + (int)(short)op;
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
                tmp1 = this.regs[rs] + (int)(short)op;
                tmp0 = this.regs[rt];
                tmp1 ^= 3;
                //System.out.printf("SWL %d\n", tmp1&3);
                this.mem_write_32_masked(tmp1&~3, tmp0>>>((tmp1&3)<<3),
                    0xFFFFFFFF>>>((tmp1&3)<<3));
                this.cycles += 1;
                break;
            case 0x2E: // SWR
                tmp1 = this.regs[rs] + (int)(short)op;
                tmp0 = this.regs[rt];
                //System.out.printf("SWR %d\n", tmp1&3);
                this.mem_write_32_masked(tmp1&~3, tmp0<<((tmp1&3)<<3),
                    0xFFFFFFFF<<((tmp1&3)<<3));
                this.cycles += 1;
                break;

            default:
                System.out.printf("%08X: %08X %02X\n", pc, op, otyp0);
                throw new RuntimeException("unsupported op");
        }
    }

    public void run_cycles(int ccount)
    {
        assert(ccount > 0);
        assert(ccount < 0x40000000);

        this.outbuf = "";
        int cyc_end = this.cycles + ccount - this.cycle_wait;

        this.need_sleep = false;
        while(!this.hard_halted && !this.need_sleep && (cyc_end - this.cycles) >= 0 && this.pc > 0x100)
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
            System.out.print(this.outbuf);
            this.outbuf = "";
        }

        if(this.pc <= 0x100)
        {
            this.hard_halted = true;
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

