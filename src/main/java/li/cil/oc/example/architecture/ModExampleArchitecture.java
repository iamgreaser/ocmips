package li.cil.oc.example.architecture;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * This mod demonstrates how to create tile entities that are treated as
 * components by OpenComputers, i.e. tile entities that provide methods which
 * can be called from a program running on a computer.
 * <p/>
 * The mod tries to keep everything else to a minimum, to focus on the mod-
 * specific parts. It is not intended for use or distribution, but you're free
 * to base a proper addon on this code.
 */
@Mod(modid = "OpenComputers|ExampleTileEntity",
        name = "OpenComputers Addon Example - TileEntity",
        version = "1.0.0",
        dependencies = "required-after:OpenComputers@[1.2.0,)")
public class ModExampleTileEntity {
    @Mod.Instance
    public static ModExampleTileEntity instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
    	li.cil.oc.api.Machine.add(PseudoArchitecture.class);
    }
}
