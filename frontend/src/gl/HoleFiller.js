export class HoleFiller {
  constructor() {
    this.gridResolution = 0
    this.gridData = null
    this.gridWidth = 0
    this.gridHeight = 0
    this.minX = 0
    this.minY = 0
    this.cellSize = 0
  }

  fill(points, targetDensity) {
    if (!points || points.length < 3) return points

    const filled = points.slice()
    const density = targetDensity || 8

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (let i = 0; i < points.length; i++) {
      if (points[i].x < minX) minX = points[i].x
      if (points[i].y < minY) minY = points[i].y
      if (points[i].x > maxX) maxX = points[i].x
      if (points[i].y > maxY) maxY = points[i].y
    }

    const rangeX = maxX - minX || 1
    const rangeY = maxY - minY || 1
    const cellSize = Math.min(rangeX, rangeY) / density

    this.minX = minX
    this.minY = minY
    this.cellSize = cellSize
    this.gridWidth = Math.ceil(rangeX / cellSize) + 1
    this.gridHeight = Math.ceil(rangeY / cellSize) + 1

    this.gridData = new Float32Array(this.gridWidth * this.gridHeight * 2)
    this.gridData.fill(NaN)

    for (let i = 0; i < points.length; i++) {
      const p = points[i]
      const gx = Math.floor((p.x - minX) / cellSize)
      const gy = Math.floor((p.y - minY) / cellSize)
      if (gx >= 0 && gx < this.gridWidth && gy >= 0 && gy < this.gridHeight) {
        const idx = (gy * this.gridWidth + gx) * 2
        this.gridData[idx] = p.z
        this.gridData[idx + 1] = p.intensity
      }
    }

    const holes = this._detectHoles()
    const filledPoints = this._interpolateHoles(holes, minX, minY, cellSize)

    for (let i = 0; i < filledPoints.length; i++) {
      filled.push(filledPoints[i])
    }

    return filled
  }

  _detectHoles() {
    const holes = []
    for (let gy = 0; gy < this.gridHeight; gy++) {
      for (let gx = 0; gx < this.gridWidth; gx++) {
        const idx = (gy * this.gridWidth + gx) * 2
        if (isNaN(this.gridData[idx])) {
          const hasNeighbor = this._hasDataNeighbor(gx, gy)
          if (hasNeighbor) {
            holes.push({ gx, gy })
          }
        }
      }
    }
    return holes
  }

  _hasDataNeighbor(gx, gy) {
    const dirs = [
      [-1, 0], [1, 0], [0, -1], [0, 1],
      [-1, -1], [1, -1], [-1, 1], [1, 1],
      [-2, 0], [2, 0], [0, -2], [0, 2]
    ]
    for (let d = 0; d < dirs.length; d++) {
      const nx = gx + dirs[d][0]
      const ny = gy + dirs[d][1]
      if (nx >= 0 && nx < this.gridWidth && ny >= 0 && ny < this.gridHeight) {
        const idx = (ny * this.gridWidth + nx) * 2
        if (!isNaN(this.gridData[idx])) return true
      }
    }
    return false
  }

  _interpolateHoles(holes, minX, minY, cellSize) {
    if (holes.length === 0) return []

    const result = []
    const maxIter = 3

    for (let iter = 0; iter < maxIter; iter++) {
      let filled = 0
      for (let h = 0; h < holes.length; h++) {
        const { gx, gy } = holes[h]
        const idx = (gy * this.gridWidth + gx) * 2
        if (!isNaN(this.gridData[idx])) continue

        const interp = this._idwInterpolate(gx, gy)
        if (interp !== null) {
          this.gridData[idx] = interp.z
          this.gridData[idx + 1] = interp.intensity
          filled++
        }
      }
      if (filled === 0) break
    }

    for (let h = 0; h < holes.length; h++) {
      const { gx, gy } = holes[h]
      const idx = (gy * this.gridWidth + gx) * 2
      if (!isNaN(this.gridData[idx])) {
        result.push({
          x: minX + gx * cellSize + cellSize / 2,
          y: minY + gy * cellSize + cellSize / 2,
          z: this.gridData[idx],
          intensity: this.gridData[idx + 1],
          beamIndex: -1,
          isInterpolated: true
        })
      }
    }

    return result
  }

  _idwInterpolate(gx, gy) {
    const radius = 3
    let sumW = 0
    let sumZ = 0
    let sumI = 0

    for (let dy = -radius; dy <= radius; dy++) {
      for (let dx = -radius; dx <= radius; dx++) {
        if (dx === 0 && dy === 0) continue
        const nx = gx + dx
        const ny = gy + dy
        if (nx < 0 || nx >= this.gridWidth || ny < 0 || ny >= this.gridHeight) continue

        const idx = (ny * this.gridWidth + nx) * 2
        if (isNaN(this.gridData[idx])) continue

        const dist = Math.sqrt(dx * dx + dy * dy)
        const w = 1.0 / (dist * dist * dist)
        sumW += w
        sumZ += w * this.gridData[idx]
        sumI += w * this.gridData[idx + 1]
      }
    }

    if (sumW === 0) return null
    return { z: sumZ / sumW, intensity: sumI / sumW }
  }
}
