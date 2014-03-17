package li.cil.oc.example.tileentity;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class BlockSimpleRadar extends Block {
    public BlockSimpleRadar() {
        super(Material.anvil);
        setCreativeTab(CreativeTabs.tabAllSearch);
        setBlockName("SimpleRadar");
    }

    @Override
    public boolean hasTileEntity(int metadata) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        return new TileEntitySimpleRadar();
    }
}
