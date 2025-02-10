/*******************************************************************************
 * Copyright (c) 2011-2014 SirSengir.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Various Contributors including, but not limited to:
 * SirSengir (original work), CovertJaguar, Player, Binnie, MysteriousAges
 ******************************************************************************/
package forestry.arboriculture.items;

import javax.annotation.Nullable;

import forestry.arboriculture.ModuleArboriculture;
import forestry.arboriculture.blocks.BlockRegistryArboriculture;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.ItemMeshDefinition;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import forestry.api.arboriculture.EnumGermlingType;
import forestry.api.arboriculture.IAlleleTreeSpecies;
import forestry.api.arboriculture.ITree;
import forestry.api.arboriculture.TreeManager;
import forestry.api.core.IModelManager;
import forestry.api.core.Tabs;
import forestry.api.genetics.AlleleManager;
import forestry.api.genetics.IAllele;
import forestry.api.genetics.IAlleleSpecies;
import forestry.api.genetics.ICheckPollinatable;
import forestry.api.genetics.IIndividual;
import forestry.api.genetics.IPollinatable;
import forestry.api.recipes.IVariableFermentable;
import forestry.arboriculture.genetics.TreeDefinition;
import forestry.arboriculture.genetics.TreeGenome;
import forestry.core.config.Config;
import forestry.core.genetics.ItemGE;
import forestry.core.items.IColoredItem;
import forestry.core.network.packets.PacketFXSignal;
import forestry.core.utils.GeneticsUtil;
import forestry.core.utils.NetworkUtil;
import forestry.core.utils.Translator;

public class ItemGermlingGE extends ItemGE implements IVariableFermentable, IColoredItem {

	private final EnumGermlingType type;

	public ItemGermlingGE(EnumGermlingType type) {
		super(Tabs.tabArboriculture);
		this.type = type;
	}

	@Override
	@Nullable
	public ITree getIndividual(ItemStack itemstack) {
		return TreeManager.treeRoot.getMember(itemstack);
	}

	@Override
	protected IAlleleTreeSpecies getSpecies(ItemStack itemStack) {
		return TreeGenome.getSpecies(itemStack);
	}

	@Override
	public String getItemStackDisplayName(ItemStack itemstack) {
		if (itemstack.getTagCompound() == null) {
			return "Unknown";
		}
		IAlleleSpecies species = getSpecies(itemstack);

		String customTreeKey = "for.trees.custom." + type.getName() + "." + species.getUnlocalizedName().replace("for.trees.species.", "");
		if (Translator.canTranslateToLocal(customTreeKey)) {
			return Translator.translateToLocal(customTreeKey);
		}
		String typeString = Translator.translateToLocal("for.trees.grammar." + type.getName() + ".type");
		return Translator.translateToLocal("for.trees.grammar." + type.getName()).replaceAll("%SPECIES", species.getAlleleName()).replaceAll("%TYPE", typeString);
	}

	@Override
	public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> subItems) {
		if (this.isInCreativeTab(tab)) {
			addCreativeItems(subItems, true);
		}
	}

	public void addCreativeItems(NonNullList<ItemStack> subItems, boolean hideSecrets) {
		for (IIndividual individual : TreeManager.treeRoot.getIndividualTemplates()) {
			// Don't show secrets unless ordered to.
			if (hideSecrets && individual.isSecret() && !Config.isDebug) {
				continue;
			}

			subItems.add(TreeManager.treeRoot.getMemberStack(individual, type));
		}
	}

	@Override
	@SideOnly(Side.CLIENT)
	public int getColorFromItemstack(ItemStack itemstack, int renderPass) {
		return TreeGenome.getSpecies(itemstack).getGermlingColour(type, renderPass);
	}

	/* MODELS */
	@SideOnly(Side.CLIENT)
	@Override
	public void registerModel(Item item, IModelManager manager) {
		manager.registerItemModel(item, new GermlingMeshDefinition());
		for (IAllele allele : AlleleManager.alleleRegistry.getRegisteredAlleles().values()) {
			if (allele instanceof IAlleleTreeSpecies) {
				((IAlleleTreeSpecies) allele).registerModels(item, manager, type);
			}
		}
	}

	@SideOnly(Side.CLIENT)
	private class GermlingMeshDefinition implements ItemMeshDefinition {
		@Override
		public ModelResourceLocation getModelLocation(ItemStack stack) {
			IAlleleTreeSpecies treeSpecies;
			if (!stack.hasTagCompound()) {
				treeSpecies = TreeDefinition.Oak.getGenome().getPrimary();
			} else {
				treeSpecies = getSpecies(stack);
			}
			return treeSpecies.getGermlingModel(type);
		}
	}

	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ) {
		IBlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		if (!block.isReplaceable(world, pos)) {
			pos = pos.offset(side);
		}

		ItemStack item = player.getHeldItem(hand);
		ITree tree = TreeManager.treeRoot.getMember(item);
		if (!item.isEmpty() && tree != null && player.canPlayerEdit(pos, side, item)) {
			if (type == EnumGermlingType.SAPLING) {
				return onItemUseSapling(item, world, player, pos, tree, side);
			} else if (type == EnumGermlingType.POLLEN) {
				return onItemUsePollen(item, world, player, pos, tree, side);
			}

			return EnumActionResult.SUCCESS;
		} else {
			return EnumActionResult.FAIL;
		}
	}

	private static EnumActionResult onItemUsePollen(ItemStack item, World world, EntityPlayer player, BlockPos pos, ITree tree, EnumFacing side) {
		ICheckPollinatable checkPollinatable = GeneticsUtil.getCheckPollinatable(world, pos);
		if (checkPollinatable == null || !checkPollinatable.canMateWith(tree)) {
			return EnumActionResult.PASS;
		}

		IPollinatable pollinatable = GeneticsUtil.getOrCreatePollinatable(player.getGameProfile(), world, pos, true);
		if (pollinatable == null || !pollinatable.canMateWith(tree)) {
			return EnumActionResult.PASS;
		}

		if (!world.isRemote) {
			pollinatable.mateWith(tree);

			IBlockState blockState = world.getBlockState(pos);
			PacketFXSignal packet = new PacketFXSignal(PacketFXSignal.VisualFXType.BLOCK_BREAK, PacketFXSignal.SoundFXType.BLOCK_BREAK, pos, blockState);
			NetworkUtil.sendNetworkPacket(packet, pos, world);

			if (!player.capabilities.isCreativeMode) {
				item.shrink(1);
			}
		}
		return EnumActionResult.SUCCESS;
	}


	private static EnumActionResult onItemUseSapling(ItemStack item, World world, EntityPlayer player, BlockPos pos, ITree tree, EnumFacing side) {
		// x, y, z are the coordinates of the block "hit", can thus either be the soil or tall grass, etc.
		IBlockState hitBlock = world.getBlockState(pos);
		if (!hitBlock.getBlock().isReplaceable(world, pos)) {
			if (!world.isAirBlock(pos.up())) {
				return EnumActionResult.PASS;
			}
			pos = pos.up();
		}

		BlockRegistryArboriculture blocks = ModuleArboriculture.getBlocks();
		if (tree.canStay(world, pos) && world.mayPlace(blocks.saplingGE, pos, false, side, player)) {
			if (TreeManager.treeRoot.plantSapling(world, tree, player.getGameProfile(), pos)) {
				if (!player.capabilities.isCreativeMode) {
					item.shrink(1);
				}
				return EnumActionResult.SUCCESS;
			}
		}
		return EnumActionResult.PASS;
	}

	@Override
	public float getFermentationModifier(ItemStack item) {
		item = GeneticsUtil.convertToGeneticEquivalent(item);
		ITree tree = TreeManager.treeRoot.getMember(item);
		if (tree == null) {
			return 1.0f;
		}
		return tree.getGenome().getSappiness() * 10;
	}

	@Override
	public int getItemBurnTime(ItemStack itemStack) {
		return 100;
	}
}
