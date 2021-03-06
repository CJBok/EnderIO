package crazypants.enderio.conduits.conduit.redstone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.enderio.core.common.util.DyeColor;
import com.enderio.core.common.util.NNList;
import com.enderio.core.common.util.NNList.NNIterator;
import com.enderio.core.common.util.NullHelper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import crazypants.enderio.base.conduit.IConduitBundle;
import crazypants.enderio.base.conduit.redstone.signals.Signal;
import crazypants.enderio.base.conduit.redstone.signals.SignalSource;
import crazypants.enderio.base.conduit.registry.ConduitRegistry;
import crazypants.enderio.conduits.conduit.AbstractConduitNetwork;
import crazypants.enderio.conduits.config.ConduitConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;

public class RedstoneConduitNetwork extends AbstractConduitNetwork<IRedstoneConduit, IRedstoneConduit> {

  private final Multimap<SignalSource, Signal> signals = ArrayListMultimap.create();

  boolean updatingNetwork = false;

  private boolean networkEnabled = true;

  public RedstoneConduitNetwork() {
    super(IRedstoneConduit.class, IRedstoneConduit.class);
  }

  @Override
  public void init(@Nonnull IConduitBundle tile, Collection<IRedstoneConduit> connections, @Nonnull World world) {
    super.init(tile, connections, world);
    updatingNetwork = true;
    notifyNeigborsOfSignalUpdate();
    updatingNetwork = false;
  }

  @Override
  public void destroyNetwork() {
    updatingNetwork = true;
    for (IRedstoneConduit con : getConduits()) {
      con.setActive(false);
    }
    // Notify neighbours that all signals have been lost
    signals.clear();
    notifyNeigborsOfSignalUpdate();
    updatingNetwork = false;
    super.destroyNetwork();
  }

  @Override
  public void addConduit(@Nonnull IRedstoneConduit con) {
    super.addConduit(con);
    updateInputsFromConduit(con, true); // all call paths to here come from updateNetwork() which already notifies all neighbors
  }

  public void updateInputsFromConduit(@Nonnull IRedstoneConduit con, boolean delayUpdate) {
    BlockPos pos = con.getBundle().getLocation();

    // Make my neighbors update as if we have no signals
    updatingNetwork = true;
    notifyConduitNeighbours(con);
    updatingNetwork = false;

    // Then ask them what inputs they have now
    Set<EnumFacing> externalConnections = con.getExternalConnections();
    for (EnumFacing side : EnumFacing.values()) {
      if (externalConnections.contains(side)) {
        updateInputsForSource(con, new SignalSource(pos, side));
      } else {
        signals.removeAll(new SignalSource(pos, side));
      }
    }

    if (!delayUpdate) {
      // then tell the whole network about the change
      notifyNeigborsOfSignalUpdate();
    }

    if (ConduitConfig.showState.get()) {
      updateActiveState();
    }
  }

  private void updateActiveState() {
    boolean isActive = false;
    for (Signal s : getSignals().values()) {
      if (s.getStrength() > 0) {
        isActive = true;
        break;
      }
    }
    for (IRedstoneConduit con : getConduits()) {
      con.setActive(isActive);
    }
  }

  private void updateInputsForSource(@Nonnull IRedstoneConduit con, @Nonnull SignalSource source) {
    updatingNetwork = true;
    signals.removeAll(source);
    Set<Signal> sigs = con.getNetworkInputs(source.getDir());
    if (sigs != null && !sigs.isEmpty()) {
      signals.putAll(source, sigs);
    }
    updatingNetwork = false;

  }

  public Multimap<SignalSource, Signal> getSignals() {
    if (networkEnabled) {
      return signals;
    } else {
      return ArrayListMultimap.create();
    }
  }

  // Need to disable the network when determining the strength of external
  // signals
  // to avoid feed back looops
  void setNetworkEnabled(boolean enabled) {
    networkEnabled = enabled;
  }

  public boolean isNetworkEnabled() {
    return networkEnabled;
  }

  @Override
  public String toString() {
    return "RedstoneConduitNetwork [signals=" + signalsString() + ", conduits=" + conduitsString() + "]";
  }

  private String conduitsString() {
    StringBuilder sb = new StringBuilder();
    for (IRedstoneConduit con : getConduits()) {
      TileEntity te = con.getBundle().getEntity();
      sb.append("<").append(te.getPos().getX()).append(",").append(te.getPos().getY()).append(",").append(te.getPos().getZ()).append(">");
    }
    return sb.toString();
  }

  String signalsString() {
    StringBuilder sb = new StringBuilder();
    for (Signal s : signals.values()) {
      sb.append("<");
      sb.append(s);
      sb.append(">");

    }
    return sb.toString();
  }

  public void notifyNeigborsOfSignalUpdate() {
    ArrayList<IRedstoneConduit> conduitsCopy = new ArrayList<IRedstoneConduit>(getConduits());
    for (IRedstoneConduit con : conduitsCopy) {
      notifyConduitNeighbours(con);
    }
  }

  private void notifyConduitNeighbours(@Nonnull IRedstoneConduit con) {
    TileEntity te = con.getBundle().getEntity();

    World world = te.getWorld();

    BlockPos bc1 = te.getPos();

    if (!world.isBlockLoaded(bc1)) {
      return;
    }

    // Done manually to avoid orphaning chunks
    EnumSet<EnumFacing> cons = EnumSet.copyOf(con.getExternalConnections());
    if (!neighborNotifyEvent(world, bc1, null, cons)) {
      for (EnumFacing dir : con.getExternalConnections()) {
        BlockPos bc2 = bc1.offset(NullHelper.notnull(dir, "Conduit external connections contains null"));
        if (world.isBlockLoaded(bc2)) {
          world.neighborChanged(bc2, ConduitRegistry.getConduitModObjectNN().getBlockNN(), bc1);
          IBlockState bs = world.getBlockState(bc2);
          if (bs.isBlockNormalCube() && !neighborNotifyEvent(world, bc2, bs, EnumSet.allOf(EnumFacing.class))) {
            for (NNIterator<EnumFacing> itr = NNList.FACING.fastIterator(); itr.hasNext();) {
              EnumFacing dir2 = itr.next();
              BlockPos bc3 = bc2.offset(dir2);
              if (!bc3.equals(bc1) && world.isBlockLoaded(bc3)) {
                world.neighborChanged(bc3, ConduitRegistry.getConduitModObjectNN().getBlockNN(), bc1);
              }
            }
          }
        }
      }
    }
  }

  private boolean neighborNotifyEvent(World world, @Nonnull BlockPos pos, @Nullable IBlockState state, EnumSet<EnumFacing> dirs) {
    return ForgeEventFactory.onNeighborNotify(world, pos, state == null ? world.getBlockState(pos) : state, dirs, false).isCanceled();
  }

  /**
   * This is a bit of a hack...avoids the network searching for inputs from unloaded chunks by only filtering out the invalid signals from the unloaded chunk.
   * 
   * @param conduits
   * @param oldSignals
   */
  public void afterChunkUnload(@Nonnull List<IRedstoneConduit> conduits, @Nonnull Multimap<SignalSource, Signal> oldSignals) {
    World world = null;
    for (IRedstoneConduit c : conduits) {
      if (world == null) {
        world = c.getBundle().getBundleworld();
      }
      BlockPos pos = c.getBundle().getLocation();
      if (world.isBlockLoaded(pos)) {
        this.getConduits().add(c);
        c.setNetwork(this);
      }
    }

    signals.clear();
    boolean signalsChanged = false;
    for (Entry<SignalSource, Signal> s : oldSignals.entries()) {
      if (world != null && world.isBlockLoaded(s.getKey().getSource())) {
        signals.put(s.getKey(), s.getValue());
      } else {
        signalsChanged = true;
      }
    }
    if (signalsChanged) {
      // broadcast out a change
      notifyNeigborsOfSignalUpdate();
    }
  }

  public int getSignalStrengthForColor(DyeColor color) {
    int strength = 0;
    for (Signal signal : signals.values()) {
      if (signal.getColor() == color && signal.getStrength() > strength) {
        strength = signal.getStrength();
      }
    }
    return strength;
  }

}
