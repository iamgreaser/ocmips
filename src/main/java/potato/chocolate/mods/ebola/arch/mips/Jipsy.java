package potato.chocolate.mods.ebola.arch.mips;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * Created by GreaseMonkey on 2/22/16.
 */
public class Jipsy {
    String outbuf = "";

    // tag notes:
    // upper 18/20 bits: phys addr upper bits
    // bottom bit: isvalid
    int[] icache_data = new int[16<<(10-2)]; // 16KB
    int[] icache_tag = new int[4<<(10-2)];
    int[] dcache_data = new int[4<<(10-2)]; // 4KB
    int[] dcache_tag = new int[4<<(10-2)];

    int[] ram;
    int[] regs;
    int[] fpregs;
    int rlo, rhi;
    int ram_bytes;
    int pc;
    int cycles;
    private int cycle_wait;
    int pf0;
    int pf0_pc;
    int op_pc;
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

    int c1_status; // 31

    private PseudoVM vm;
    static final int OUTBUF_LEN = 1;
    boolean interrupt_ready;
    boolean interrupt_timer_enabled = false;
    long interrupt_timer_next = 0;

    // 0 = entrylo, 1 = entryhi, 2 = next in queue, 3 = previous in queue
    int[][] tlb_entries = new int [64][4];
    int tlb_entry_most_recent = 0;

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

        // TODO: make this do less, then make the bootloader do more.

        // reset TLB
        this.reset_tlb();

        // clear cache
        Arrays.fill(this.icache_data, 0);
        Arrays.fill(this.icache_tag, 0);
        Arrays.fill(this.dcache_data, 0);
        Arrays.fill(this.dcache_tag, 0);

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

        // reset COP1
        this.c1_status = 0x00000000;

        // clear prefetch buffer
        this.pf0_pc = this.pc;
        this.pf0 = 0x00000000; // NOP
    }

    public Jipsy(PseudoVM vm, int ram_bytes_)
    {
        assert(ram_bytes_ >= 4);
        assert(ram_bytes_ <= (256<<20)); // cap at... is 256MB enough?

        // pad to 4-byte boundary
        ram_bytes_ = (ram_bytes_+3)&~3;

        this.vm = vm;
        this.ram_bytes = ram_bytes_;
        this.ram = new int[ram_bytes_>>2];
        this.regs = new int[32];
        this.fpregs = new int[32];
        this.cycles = 0;
        this.cycle_wait = 0;
        this.hard_halted = true;
    }

    private boolean mem_does_use_cache(int vaddr_)
    {
        if((vaddr_&0xE0000000) == 0xA0000000) {
            return false;
        }

        if((vaddr_&0xE0000000) == 0x80000000) {
            return true;
        }

        if((this.tlb_entries[this.tlb_entry_most_recent][0] & 0x800) != 0) {
            return false;
        }

        return true;
    }

    public int mem_read_32_real(int addr_)
    {
        if(addr_ >= 0x1FF00000) {
            return vm.busReadMask32(addr_, 0xFFFFFFFF); // TODO proper mask
        } else {
            return this.ram[addr_ >>> 2];
        }
    }

    public int mem_read_32_dcached(int paddr_, int vaddr_)
    {
        int ret;

        if((c0_status & (1<<16)) != 0) { // IsC - Isolate Cache
            c0_status &= ~(1<<19); // CM - Cache Miss (IDT docs state the opposite of what Linux expects)

            if((c0_status & (1<<17)) != 0) { // SwC - Swap Caches
                // read from icache
                ret = this.icache_data[(paddr_>>2)&0xFFF];
                if(((this.icache_tag[(paddr_>>4)&0x3FF]^(paddr_|1))&~0x3FFE) != 0) {
                    c0_status |= (1<<19);
                }
            } else {
                // read from dcache
                ret = this.dcache_data[(paddr_>>2)&0x3FF];
                if(((this.dcache_tag[(paddr_>>2)&0x3FF]^(paddr_|1))&~0xFFE) != 0) {
                    c0_status |= (1<<19);
                }
            }

            return ret;
        }

        boolean use_cache = mem_does_use_cache(vaddr_);

        if(use_cache) {
            if (((this.dcache_tag[(paddr_ >> 2) & 0x3FF] ^ (paddr_|1)) & ~0xFFE) == 0) {
                // we're all good
                return this.dcache_data[(paddr_ >> 2) & 0x3FF];
            }

            ret = mem_read_32_real(paddr_);
            this.dcache_data[(paddr_>>2)&0x3FF] = ret;
            this.dcache_tag[(paddr_>>2)&0x3FF] = (paddr_&~0xFFF)|1;
            return ret;
        } else {
            return mem_read_32_real(paddr_);
        }
    }

    public int mem_read_32(int paddr_, int vaddr_)
    {
        if((paddr_&3) != 0) {
            throw new RuntimeException("misaligned 32-bit read");
        }

        return mem_read_32_dcached(paddr_, vaddr_);
    }

    public short mem_read_16(int paddr_, int vaddr_)
    {
        if((paddr_&1) != 0) {
            throw new RuntimeException("misaligned 16-bit read");
        }

        return (short)(mem_read_32_dcached(paddr_, vaddr_)>>((paddr_&3)*8));
    }

    public byte mem_read_8(int paddr_, int vaddr_)
    {
        return (byte)(mem_read_32_dcached(paddr_, vaddr_)>>((paddr_&3)*8));
    }

    public String mem_read_cstr(int addr_)
    {
        int len = 0;
        ByteArrayOutputStream bfp = new ByteArrayOutputStream();

        // cap it here
        while(len < 65536)
        {
            // stop if we aren't even in memory
            int iaddr = (addr_ + len) & 0x1FFFFFFF;
            if(iaddr >= this.ram_bytes)
                break;

            byte b = mem_read_8(iaddr, iaddr | 0xA0000000);
            this.cycles += 1;
            if(b == 0) break;
            bfp.write(0xFF&(int)b);
            len++;
        }

        //return bfp.toString("UTF-8");
        //return bfp.toString("ISO-8859-1");
        try {
            //return bfp.toString("ISO-8859-1");
            return bfp.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public byte[] mem_read_pstr_bytes(int addr_, int slen_)
    {
        ByteArrayOutputStream bfp = new ByteArrayOutputStream();

        // cap it here
        int len = 0;
        while(len < 65536)
        {
            // stop if we aren't even in memory
            int iaddr = (addr_ + len) & 0x1FFFFFFF;
            if(iaddr >= this.ram_bytes)
                break;

            if(len >= slen_) break;
            byte b = mem_read_8(iaddr, iaddr | 0xA0000000);
            this.cycles += 1;
            bfp.write(0xFF&(int)b);
            len++;
        }

        //return bfp.toString("UTF-8");
        return bfp.toByteArray();
    }

    private int mem_icache_fetch(int addr_)
    {
        if((c0_status & (1<<17)) != 0) {
            // i really don't want to have to do this but i may need to

            //System.out.printf("SwC ifetch dcache %08X\n", addr_);

            int tidx = (addr_>>2)&0x3FF;
            int didx = (addr_>>2)&0x3FF;

            if(((this.dcache_tag[tidx]^(addr_|1)) & ~0xFFE) != 0) {
                // tag mismatch, load new stuff
                this.dcache_tag[tidx] = (addr_ & ~0xFFF);
                this.dcache_data[didx] = this.mem_read_32_real(addr_&~3);
                this.cycles++;
                //System.out.printf("cache miss, load %08X\n", this.dcache_data[didx]);
                this.dcache_tag[tidx] |= 1;
            }

            return this.dcache_data[didx];
        }

        int tidx = (addr_>>4)&0x3FF;
        int didx = (addr_>>2)&0xFFF;

        if(((this.icache_tag[tidx]^(addr_|1)) & ~0x3FFE) != 0) {
            // tag mismatch, load new stuff
            this.icache_tag[tidx] = (addr_ & ~0x3FFF);

            for(int i = 0; i < 4; i++) {
                this.icache_data[(tidx<<2)+i] = this.mem_read_32_real((addr_&~0xF) + (i<<2));
                this.cycles++;
            }

            this.icache_tag[tidx] |= 1;
        }

        return this.icache_data[didx];
    }

    public void mem_write_32_masked_real(int paddr_, int vaddr_, int data_, int mask_)
    {
        //System.out.printf("write %08X %08X %08X\n", paddr_, data_, mask_);
        if(paddr_ >= 0x1FF00000) {
            vm.busWriteMask32(paddr_, data_, mask_);
            return;
        }

        this.ram[paddr_ >>> 2] &= ~mask_;
        this.ram[paddr_ >>> 2] |= data_ & mask_;
    }

    public void mem_write_32_masked(int paddr_, int vaddr_, int data_, int mask_)
    {
        if(false) {
            mem_write_32_masked_real(paddr_, vaddr_, data_, mask_);
            return;
        }

        if((c0_status & (1<<16)) != 0) { // IsC - Isolate Cache
            if((c0_status & (1<<17)) != 0) { // SwC - Swap Caches
                // write to icache
                if(mask_ == -1) {
                    // set tag directly
                    this.icache_tag[(paddr_>>4)&0x3FF] = (paddr_ & ~0x3FFF) | 1;
                    this.icache_data[(paddr_>>2)&0xFFF] = data_;

                } else {
                    // invalidate line
                    this.icache_tag[(paddr_>>4)&0x3FF] &= ~1;
                    //this.icache_data[(paddr_>>2)&0xFFF] &= ~mask_;
                    //this.icache_data[(paddr_>>2)&0xFFF] |= data_ & mask_;
                }


            } else {
                // write to dcache
                if(mask_ == -1) {
                    // set tag directly
                    this.dcache_tag[(paddr_>>2)&0x3FF] = (paddr_ & ~0xFFF) | 1;
                    this.dcache_data[(paddr_>>2)&0x3FF] = data_;

                } else {
                    // invalidate line
                    this.dcache_tag[(paddr_>>2)&0x3FF] &= ~1;
                    //this.dcache_data[(paddr_>>2)&0x3FF] &= ~mask_;
                    //this.dcache_data[(paddr_>>2)&0x3FF] |= data_ & mask_;
                }

            }

            return;
        }

        // FIXME: support uncacheable bit of TLB
        boolean use_cache = mem_does_use_cache(vaddr_);

        // TODO: deal with SwC=1 IsC=0 somehow? I'm not sure what to do here.
        // We can either emulate it plausibly, outright ignore it, or error.
        if(use_cache) {
            // check if we can put this into the cache
            if(mask_ == -1) {
                // add to cache
                this.dcache_data[(paddr_>>2)&0x3FF] = data_;
                this.dcache_tag[(paddr_>>2)&0x3FF] = (paddr_ & ~0xFFF) | 1;

            } else if(((this.dcache_tag[(paddr_>>2)&0x3FF]^(paddr_|1)) & ~0xFFE) == 0) {
                // already in cache, mask in the new stuff
                this.dcache_data[(paddr_>>2)&0x3FF] &= ~mask_;
                this.dcache_data[(paddr_>>2)&0x3FF] |= data_ & mask_;
            }

            // if neither pass, the cache is unaffected
        }

        // it's a write-through cache, so we write it
        // TODO: 4-stage write buffer
        mem_write_32_masked_real(paddr_, vaddr_, data_, mask_);
    }

    public void mem_write_32(int paddr_, int vaddr_, int data_)
    {
        if((paddr_&3) != 0) {
            throw new RuntimeException("misaligned 32-bit write");
        }

        mem_write_32_masked(paddr_, vaddr_, data_, 0xFFFFFFFF);
    }

    public void mem_write_16(int paddr_, int vaddr_, short data_)
    {
        if((paddr_&1) != 0) {
            throw new RuntimeException("misaligned 16-bit write");
        }

        mem_write_32_masked(paddr_, vaddr_, (0xFFFF&(int)data_)*0x00010001, 0xFFFF<<((paddr_&2)*8));
    }

    public void mem_write_8(int paddr_, int vaddr_, byte data_)
    {
        mem_write_32_masked(paddr_, vaddr_, (0xFF&(int)data_)*0x01010101, 0xFF<<((paddr_&3)*8));
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
            if((phys_pc&3) != 0)
            {
                System.out.printf("PC ~ %08X p=%08X\n", this.pc, phys_pc);
                throw new RuntimeException("misaligned 32-bit ifetch");
            }

            // WARNING: the last call to remap_address() must be for this.pc,
            // otherwise the cache check will fail!
            int new_op = (mem_does_use_cache(this.pc)
                ? this.mem_icache_fetch(phys_pc)
                : this.mem_read_32_real(phys_pc));

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
                                    tmp0 = 0x00000200; // R3000 works fine... apparently 3 == R6000?!
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
                                    tmp1 = 0x3043FF3F;
                                    c0_status = (c0_status & ~tmp1) | (tmp0 & tmp1);
                                    if((c0_status & (3<<16)) == (2<<16)) {
                                        throw new RuntimeException("SwC without IsC is not supported");
                                    }
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
                    int fs2 = rt;
                    int fs1 = rd;
                    int fd = sh;
                    if (rs >= 16) switch (rs) {

                        // TODO: make stuff fall back to sw emu
                        // TODO: work out what to do when odd regs are used
                        case 16: // SP
                        {
                            float vs1 = Float.intBitsToFloat(this.fpregs[fs1]);
                            float vs2 = Float.intBitsToFloat(this.fpregs[fs2]);
                            float vd = 0.0f;
                            switch (otyp1) {
                                case 0: // ADD
                                    vd = vs1 + vs2;
                                    break;
                                case 1: // SUB
                                    vd = vs1 - vs2;
                                    break;
                                case 2: // MUL
                                    vd = vs1 * vs2;
                                    break;
                                case 3: // DIV
                                    vd = vs1 / vs2;
                                    break;
                                case 5: // ABS
                                    vd = Math.abs(vs1);
                                    break;
                                case 6: // MOV
                                    vd = vs1;
                                    break;
                                case 7: // NEG
                                    vd = -vs1;
                                    break;

                                case 33: {
                                    // CVT from S to D
                                    float cfrom = vs1;
                                    double cto = (double)cfrom;
                                    long vdi = Double.doubleToLongBits(cto);
                                    fd &= ~1;
                                    this.fpregs[fd+0] = (int)(vdi);
                                    this.fpregs[fd+1] = (int)(vdi>>>32);
                                    fd = -1;
                                } break;
                                case 36: {
                                    // CVT from S to W
                                    float cfrom = vs1;
                                    int cto = (int)cfrom;
                                    this.fpregs[fd] = cto;
                                    fd = -1;
                                } break;

                                default:
                                    if(otyp1 >= 48 && otyp1 <= 63) {
                                        // TODO: trap on trapping compares
                                        boolean cond = false;
                                        if((otyp1 & 4) != 0 && vs1 < vs2) { cond = true; }
                                        if((otyp1 & 2) != 0 && vs1 == vs2) { cond = true; }
                                        if((otyp1 & 1) != 0 && (vs1 < vs2) == (vs1 > vs2)) { cond = true; }
                                        if(cond) {
                                            this.c1_status |= (1<<23);
                                        } else {
                                            this.c1_status &= ~(1<<23);
                                        }
                                        fd = -1;
                                        break;
                                    }
                                    // TODO: use IRQ3 trap
                                    System.out.printf("%08X: %08X %02X (S FP)\n", pc, op, rs);
                                    this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                                    return;

                            }

                            if (fd != -1) {
                                this.fpregs[fd] = Float.floatToIntBits(vd);
                            }
                        } break;
                        case 17: // DP
                        {
                            fs1 &= ~1; fs2 &= ~1; fd &= ~1;
                            double vs1 = Double.longBitsToDouble(
                                (((long)this.fpregs[fs1+0])&0xFFFFFFFFL)
                                |((((long)this.fpregs[fs1+1])&0xFFFFFFFFL)<<32L)
                            );
                            double vs2 = Double.longBitsToDouble(
                                (((long)this.fpregs[fs2+0])&0xFFFFFFFFL)
                                |((((long)this.fpregs[fs2+1])&0xFFFFFFFFL)<<32L)
                            );
                            //System.out.printf("op %f %f\n", vs1, vs2);
                            double vd = 0.0f;
                            switch(otyp1) {
                                case 0: // ADD
                                    vd = vs1 + vs2;
                                    break;
                                case 1: // SUB
                                    vd = vs1 - vs2;
                                    break;
                                case 2: // MUL
                                    vd = vs1 * vs2;
                                    break;
                                case 3: // DIV
                                    vd = vs1 / vs2;
                                    break;
                                case 5: // ABS
                                    vd = Math.abs(vs1);
                                    break;
                                case 6: // MOV
                                    vd = vs1;
                                    break;
                                case 7: // NEG
                                    vd = -vs1;
                                    break;

                                case 32: {
                                    // CVT from D to S
                                    double cfrom = vs1;
                                    float cto = (float)cfrom;
                                    this.fpregs[fd] = Float.floatToIntBits(cto);
                                    fd = -1;
                                } break;
                                case 36: {
                                    // CVT from D to W
                                    double cfrom = vs1;
                                    int cto = (int)cfrom;
                                    this.fpregs[fd] = cto;
                                    fd = -1;
                                } break;

                                default:
                                    if(otyp1 >= 48 && otyp1 <= 63) {
                                        // TODO: trap on trapping compares
                                        boolean cond = false;
                                        if((otyp1 & 4) != 0 && vs1 < vs2) { cond = true; }
                                        if((otyp1 & 2) != 0 && vs1 == vs2) { cond = true; }
                                        if((otyp1 & 1) != 0 && (vs1 < vs2) == (vs1 > vs2)) { cond = true; }
                                        if(cond) {
                                            this.c1_status |= (1<<23);
                                        } else {
                                            this.c1_status &= ~(1<<23);
                                        }
                                        fd = -1;
                                        break;
                                    }
                                    // TODO: use IRQ3 trap
                                    System.out.printf("%08X: %08X %02X (D FP)\n", pc, op, rs);
                                    this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                                    return;

                            }

                            if (fd != -1) {
                                long vdi = Double.doubleToLongBits(vd);
                                this.fpregs[fd+0] = (int)(vdi);
                                this.fpregs[fd+1] = (int)(vdi>>>32);
                            }
                        } break;

                        case 20: // W
                            switch(otyp1) {
                                case 32: {
                                    // CVT from W to S
                                    int cfrom = this.fpregs[fs1];
                                    float cto = (float)cfrom;
                                    this.fpregs[fd] = Float.floatToIntBits(cto);
                                    fd = -1;
                                } break;
                                case 33: {
                                    // CVT from W to D
                                    int cfrom = this.fpregs[fs1];
                                    double cto = (double)cfrom;
                                    long vdi = Double.doubleToLongBits(cto);
                                    fd &= ~1;
                                    this.fpregs[fd+0] = (int)(vdi);
                                    this.fpregs[fd+1] = (int)(vdi>>>32);
                                    fd = -1;
                                } break;

                                default:
                                    // TODO: use IRQ3 trap
                                    System.out.printf("%08X: %08X %02X (W FP)\n", pc, op, rs);
                                    this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                                    return;

                            }
                            break;

                        default:
                            // TODO: use IRQ3 trap
                            System.out.printf("%08X: %08X %02X (FP type)\n", pc, op, rs);
                            this.isr(EX_RI, 0); // CU is only defined for EX_CpU
                            return;

                    }

                    else switch (rs) {
                        case 0: // MFCn
                            tmp0 = this.fpregs[rd];
                            if (rt != 0)
                                this.regs[rt] = tmp0;
                            break;
                        case 2: // CFCn
                            switch (rd) {
                                case 0: // FCR0: Revision
                                    tmp0 = 0x0300;
                                    break;
                                case 31: // FCR31: Status
                                    tmp0 = this.c1_status;
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
                            this.fpregs[rd] = tmp0;
                            break;

                        case 6: // CTCn
                            switch (rd) {
                                case 31: // FCR31: Status
                                    // TODO emulate properly
                                    this.c1_status = this.regs[rt];
                                    break;
                                default:
                                    System.out.printf("%08X: %08X %02X (CTC1)\n", pc, op, rs);
                                    this.isr(EX_RI, 0);
                                    return;
                            } break;
                        case 8: // BCnb
                            if(((this.c1_status>>23)&1) == (rt&1)) {
                                this.pc = pc + 4 + (((int)(short)op)<<2);
                                this.bd_detect = this.op_pc+4;
                            }
                            break;
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
                tmp0 = (int)this.mem_read_8(tmp1, tmp0);
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
                tmp0 = (int)this.mem_read_16(tmp1, tmp0);
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
                tmp0 = this.mem_read_32(tmp1, tmp0);
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
                tmp0 = 0xFF&((int)(this.mem_read_8(tmp1, tmp0)));
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
                tmp0 = 0xFFFF&((int)(this.mem_read_16(tmp1, tmp0)));
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
                this.mem_write_8(tmp1, tmp0, (byte)this.regs[rt]);
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
                this.mem_write_16(tmp1, tmp0, (short)this.regs[rt]);
                this.cycles += 1;
                break;
            case 0x2B: // SW
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                //System.out.printf("reg %2d %08X %08X -> %08X -> %08X\n", rs, op, this.regs[rs], tmp0, tmp1);
                if(tmp1 >= 0 && (tmp1&3) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                this.mem_write_32(tmp1, tmp0, this.regs[rt]);
                this.cycles += 1;
                break;

            case 0x31: // LWC1
                // TODO: work out what kind of fault *actually* happens
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 >= 0 && (tmp1&3) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.mem_read_32(tmp1, tmp0);
                this.fpregs[rt] = tmp0;
                this.cycles += 1;
                break;
            case 0x39: // SWC1
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                //System.out.printf("reg %2d %08X %08X -> %08X -> %08X\n", rs, op, this.regs[rs], tmp0, tmp1);
                if(tmp1 >= 0 && (tmp1&3) != 0) { tmp1=-1-EX_AdEL; c0_vaddr = tmp0; }
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                this.mem_write_32(tmp1, tmp0, this.fpregs[rt]);
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
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.mem_read_32(tmp1&~3, tmp0&~3);
                tmp1 ^= 3;
                this.cycles += 1;
                if(rt != 0) this.regs[rt] = (this.regs[rt] & ~(0xFFFFFFFF<<((tmp1&3)<<3)))
                    | (tmp0<<((tmp1&3)<<3));
                //System.out.printf("LWL %08X %d\n", this.regs[rt], tmp1&3);
                break;
            case 0x26: // LWR
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, false);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    this.isr(fault, 0);
                    return;
                }
                tmp0 = this.mem_read_32(tmp1&~3, tmp0&~3);
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
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                tmp1 ^= 3;
                //System.out.printf("SWL %d\n", tmp1&3);
                this.mem_write_32_masked(tmp1&~3, tmp0&~3, (this.regs[rt])>>>((tmp1&3)<<3),
                    0xFFFFFFFF>>>((tmp1&3)<<3));
                this.cycles += 1;
                break;
            case 0x2E: // SWR
                tmp0 = this.regs[rs] + (int)(short)op;
                tmp1 = this.remap_address(tmp0, true);
                if(tmp1 < 0) {
                    int fault = -tmp1-1;
                    if(fault == EX_TLBL) fault = EX_TLBS;
                    if(fault == EX_AdEL) fault = EX_AdES;
                    this.isr(fault, 0);
                    return;
                }
                //System.out.printf("SWR %d\n", tmp1&3);
                this.mem_write_32_masked(tmp1&~3, tmp0&~3, this.regs[rt]<<((tmp1&3)<<3),
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

