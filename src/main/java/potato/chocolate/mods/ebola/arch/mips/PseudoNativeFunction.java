package potato.chocolate.mods.ebola.arch.mips;

/** Interface defining callbacks provided by the host. */
public interface PseudoNativeFunction {
  Object invoke(Object[] args);
}
