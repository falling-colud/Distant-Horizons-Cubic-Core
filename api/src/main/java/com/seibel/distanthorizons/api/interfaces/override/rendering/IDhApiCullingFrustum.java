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

package com.seibel.distanthorizons.api.interfaces.override.rendering;

import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

/**
 * Used to determine if a LOD should be rendered or is outside the
 * user's field of view.
 * 
 * @author James Seibel
 * @version 2024-2-6
 * @since API 2.0.0
 */
public interface IDhApiCullingFrustum extends IDhApiOverrideable
{
	
	/** 
	 * Called before a render pass is done.
	 * 
	 * @param worldMinBlockY the lowest block position this level allows.
	 * @param worldMaxBlockY the highest block position this level allows.
	 * @param worldViewProjection the projection matrix used in this render pass.
	 */
	void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f worldViewProjection);
	
	/** 
	 * returns true if the LOD bounds intersect this frustum 
	 * 
	 * @param lodBlockPosMinX this LOD's starting block X position closest to negative infinity
	 * @param lodBlockPosMinZ this LOD's starting block Z position closest to negative infinity
	 * @param lodBlockWidth this LOD's width in blocks
	 * @param lodDetailLevel this LOD's detail level
	 * 
	 * @see EDhApiDetailLevel
	 */
	boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel);

	/**
	 * Like {@link #intersects(int, int, int, int)}, for LODs that only cover part of the world's height
	 * (the cubic LODs of cubic chunks levels). <br>
	 * By default this ignores the height and tests the whole column.
	 *
	 * @param lodBlockPosMinY the lowest block Y of the LOD
	 * @param lodBlockHeight how many blocks tall the LOD is
	 * @since API 7.2.0 (cubic)
	 */
	default boolean intersects(int lodBlockPosMinX, int lodBlockPosMinY, int lodBlockPosMinZ, int lodBlockWidth, int lodBlockHeight, int lodDetailLevel)
	{ return this.intersects(lodBlockPosMinX, lodBlockPosMinZ, lodBlockWidth, lodDetailLevel); }
	
}
