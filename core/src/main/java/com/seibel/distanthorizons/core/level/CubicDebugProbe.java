/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;

/**
 * Debugging aid for cubic LODs, for test harnesses (called by reflection, nothing in
 * Distant Horizons uses it): what the client's LOD tree and database hold for the
 * sections containing a block position, at every detail level.
 */
public final class CubicDebugProbe
{
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);



	private CubicDebugProbe() { }



	/** One line per section detail level, from the finest to the coarsest. */
	public static String describe(int blockX, int blockY, int blockZ)
	{
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		IClientLevelWrapper clientLevel = MC_CLIENT.getWrappedClientLevel();
		if (world == null || clientLevel == null)
		{
			return "no DH world or client level";
		}

		IDhLevel level = null;
		ClientLevelModule clientside = null;
		for (IDhLevel candidate : world.getAllLoadedLevels())
		{
			if (!candidate.getLevelWrapper().getDimensionName().equals(clientLevel.getDimensionName()))
			{
				continue;
			}

			if (candidate instanceof DhClientServerLevel)
			{
				level = candidate;
				clientside = ((DhClientServerLevel) candidate).clientside;
			}
			else if (candidate instanceof DhClientLevel)
			{
				level = candidate;
				clientside = ((DhClientLevel) candidate).clientside;
			}
		}
		if (level == null)
		{
			return "no DH level for " + clientLevel.getDimensionName();
		}

		FullDataSourceProviderV2 provider = level.getFullDataProvider();
		ClientLevelModule.ClientRenderState renderState = (clientside != null) ? clientside.ClientRenderStateRef.get() : null;
		StringBuilder out = new StringBuilder();
		out.append("cubic=").append(level.isCubic());
		if (renderState != null)
		{
			out.append(" treeCenterY=").append(renderState.quadtree.getPlayerBlockY())
				.append(" verticalRadius=").append(renderState.verticalRadiusInBlocks);
		}

		for (byte detail = DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL; detail <= DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL + 6; detail++)
		{
			long pos = level.isCubic()
				? DhSectionPos.encodeContainingCubic(detail, blockX, blockY, blockZ)
				: DhSectionPos.encodeContaining(detail, new com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos(blockX, blockY, blockZ));
			out.append("\n  ").append(DhSectionPos.toString(pos));
			if (DhSectionPos.isCubic(pos))
			{
				out.append(" Y[").append(DhSectionPos.getCubicMinBlockY(pos)).append(',')
					.append(DhSectionPos.getCubicMinBlockY(pos) + DhSectionPos.getCubicBlockHeight(pos)).append(')');
			}

			// database
			try
			{
				if (provider == null)
				{
					out.append(" noProvider");
				}
				else if (!provider.repo.existsWithKey(pos))
				{
					out.append(" db=none");
				}
				else
				{
					ByteArrayList steps = new ByteArrayList(FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
					provider.repo.getColumnGenerationStepForPos(pos, steps);
					int[] counts = new int[16];
					for (int i = 0; i < steps.size(); i++)
					{
						counts[steps.getByte(i) & 15]++;
					}
					out.append(" db[");
					for (int step = 0; step < counts.length; step++)
					{
						if (counts[step] != 0)
						{
							EDhApiWorldGenerationStep genStep = EDhApiWorldGenerationStep.fromValue((byte) step);
							out.append(genStep != null ? genStep.name() : Integer.toString(step)).append('=').append(counts[step]).append(' ');
						}
					}
					out.append(']');
				}
			}
			catch (Exception e)
			{
				out.append(" dbError=").append(e.getMessage());
			}

			// render tree
			try
			{
				if (renderState == null)
				{
					out.append(" tree=none");
				}
				else
				{
					QuadNode<LodRenderSection> node = renderState.quadtree.getNode(pos);
					LodRenderSection section = (node != null) ? node.value : null;
					if (section == null)
					{
						out.append(node == null ? " node=none" : " node=empty");
					}
					else
					{
						out.append(" rendering=").append(section.getRenderingEnabled())
							.append(" canRender=").append(section.canRender())
							.append(" uploaded=").append(section.gpuUploadComplete())
							.append(" noData=").append(section.loadedWithoutData);
					}
				}
			}
			catch (Exception e)
			{
				out.append(" tree=outside");
			}
		}
		return out.toString();
	}

}
