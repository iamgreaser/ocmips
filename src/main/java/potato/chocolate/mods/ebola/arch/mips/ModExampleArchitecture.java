package potato.chocolate.mods.ebola.arch.mips;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import li.cil.oc.api.fs.FileSystem;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * This mod demonstrates how to create tile entities that are treated as
 * components by OpenComputers, i.e. tile entities that provide methods which
 * can be called from a program running on a computer.
 * <p/>
 * The mod tries to keep everything else to a minimum, to focus on the mod-
 * specific parts. It is not intended for use or distribution, but you're free
 * to base a proper addon on this code.
 */
@Mod(modid = "OpenComputers|MIPS",
        name = "MIPS for OpenComputers",
        version = "1.0.0",
        dependencies = "required-after:OpenComputers@[1.4.0,)")
public class ModExampleArchitecture {
    @Mod.Instance
    public static ModExampleArchitecture instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent ev) {
        li.cil.oc.api.Machine.add(PseudoArchitecture.class);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent ev) {
        byte[] elf_code = new byte[4096];
        try {
            int code_len = this.getClass().getResourceAsStream("/boot.bin").read(elf_code, 0, 4096);
            li.cil.oc.api.Items.registerEEPROM("MIPS ELF BIOS", elf_code, null, true);
            System.out.printf("Length of boot.bin: %d bytes\n", code_len);
        } catch(IOException e) {
            System.err.printf("Could not load boot.bin!\n");
            e.printStackTrace();
        } catch(Exception e) {
            System.err.printf("Could not load boot.bin!\n");
            e.printStackTrace();
        }

        li.cil.oc.api.Items.registerFloppy("MLua53", 0, new Callable<FileSystem>() {
            @Override
            public FileSystem call() throws Exception {
                return li.cil.oc.api.FileSystem.fromClass(this.getClass(),
                         "ocmips", "loot/lua53/");
            }
        });
    }
}
