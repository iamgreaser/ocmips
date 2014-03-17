package li.cil.oc.example.tileentity;

import li.cil.oc.api.network.Arguments;
import li.cil.oc.api.network.Callback;
import li.cil.oc.api.network.Context;
import li.cil.oc.api.network.SimpleComponent;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// This is a simplified version of TileEntityRadar, in that it does not use
// energy, but it also doesn't have to implement the Environment interface
// manually. Instead, it uses the utility interface SimpleComponent, which,
// when found by OpenComputers will trigger injection of all functionality
// required for a component to work.
public class TileEntitySimpleRadar extends TileEntity implements SimpleComponent {
    public static final double RadarRange = 32;

    protected boolean isEnabled = true;

    @Override
    public String getComponentName() {
        return "simple_radar";
    }

    // The following methods will be callable from Lua due to the Callback
    // annotation. Methods in an environment specified as the owner of a
    // component node are searched for this annotation. Note that methods
    // annotated with the Callback annotation must have the exact signature the
    // following methods have. The returned array is treated as a 'tuple' when
    // pushed to the computer, i.e. as multiple returned values.

    @Callback
    public Object[] isEnabled(Context context, Arguments args) {
        return new Object[]{isEnabled};
    }

    @Callback
    public Object[] setEnabled(Context context, Arguments args) {
        isEnabled = args.checkBoolean(0);
        return new Object[]{isEnabled};
    }

    @Callback
    public Object[] getEntities(Context context, Arguments args) {
        List<Map> entities = new ArrayList<Map>();
        if (isEnabled) {
            // Get a initial list of entities near the tile entity.
            AxisAlignedBB bounds = AxisAlignedBB.
                    getBoundingBox(xCoord, yCoord, zCoord, xCoord + 1, yCoord + 1, zCoord + 1).
                    expand(RadarRange, RadarRange, RadarRange);
            for (Object obj : getWorldObj().getEntitiesWithinAABB(EntityLivingBase.class, bounds)) {
                EntityLivingBase entity = (EntityLivingBase) obj;
                double dx = entity.posX - (xCoord + 0.5);
                double dz = entity.posZ - (zCoord + 0.5);
                // Check if the entity is actually in range.
                if (Math.sqrt(dx * dx + dz * dz) < RadarRange) {
                    // Maps are converted to tables on the Lua side.
                    Map<String, Object> entry = new HashMap<String, Object>();
                    entry.put("name", entity.getEntityName());
                    entry.put("x", (int) dx);
                    entry.put("z", (int) dz);
                    entities.add(entry);
                }
            }

            // Force the computer that made the call to sleep for a bit, to
            // avoid calling this method excessively (since it could be quite
            // expensive). The time is specified in seconds.
            context.pause(0.5);
        }

        // The returned array is treated as a tuple, meaning if we return the
        // entities as an array directly, we'd end up with each entity as an
        // individual result value (i.e. in Lua we'd have to write
        //   result = {radar.getEntities()}
        // and we'd be limited in the number of entities, due to the limit of
        // return values. So we wrap it in an array to return it as a list.
        return new Object[]{entities.toArray()};
    }
}
