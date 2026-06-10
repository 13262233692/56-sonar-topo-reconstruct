export class SonarSimulatorFrontend {
  constructor(onFrame) {
    this.onFrame = onFrame
    this._running = false
    this._timerId = null
    this._pingCounter = 0
    this._numBeams = 128
    this._maxSwathAngle = 75.0
    this._baseDepth = 250.0
    this._holeRegions = []
    this._initHoleRegions()
  }

  _initHoleRegions() {
    this._holeRegions = [
      { centerAngle: -15, angleWidth: 25, yStart: 100, yEnd: 400 },
      { centerAngle: 30, angleWidth: 20, yStart: 200, yEnd: 500 },
      { centerAngle: -45, angleWidth: 15, yStart: 50, yEnd: 250 },
      { centerAngle: 0, angleWidth: 40, yStart: 600, yEnd: 800 },
      { centerAngle: 50, angleWidth: 18, yStart: 150, yEnd: 350 }
    ]
  }

  start() {
    if (this._running) return
    this._running = true
    this._tick()
  }

  stop() {
    this._running = false
    if (this._timerId) {
      clearInterval(this._timerId)
      this._timerId = null
    }
  }

  _tick() {
    this._timerId = setInterval(() => {
      if (!this._running) return
      const frame = this._generateFrame()
      if (this.onFrame) this.onFrame(frame)
    }, 100)
  }

  _isInHole(beamAngle, shipY) {
    for (let i = 0; i < this._holeRegions.length; i++) {
      const hole = this._holeRegions[i]
      const angleDist = Math.abs(beamAngle - hole.centerAngle)
      if (angleDist < hole.angleWidth / 2 && shipY >= hole.yStart && shipY <= hole.yEnd) {
        return true
      }
    }
    if (Math.random() < 0.03) {
      return true
    }
    return false
  }

  _generateFrame() {
    const points = []
    const timestamp = Date.now()
    const pingNumber = this._pingCounter++

    const shipDriftX = Math.sin(pingNumber * 0.02) * 50
    const shipDriftY = pingNumber * 0.5

    for (let i = 0; i < this._numBeams; i++) {
      const beamAngle = -this._maxSwathAngle + (2 * this._maxSwathAngle * i / (this._numBeams - 1))

      if (this._isInHole(beamAngle, shipDriftY)) {
        continue
      }

      const beamAngleRad = beamAngle * Math.PI / 180

      let terrainVariation = 0
      terrainVariation += 30 * Math.sin(beamAngleRad * 3 + pingNumber * 0.05)
      terrainVariation += 15 * Math.sin(beamAngleRad * 7 + pingNumber * 0.03)
      terrainVariation += 8 * Math.cos(beamAngleRad * 13 + pingNumber * 0.07)
      terrainVariation += 5 * Math.sin(beamAngleRad * 21 + pingNumber * 0.11)

      let seamount = 0
      const distFromCenter = Math.abs(beamAngle)
      if (distFromCenter < 30) {
        seamount = 80 * Math.exp(-distFromCenter * distFromCenter / 200) * Math.sin(pingNumber * 0.04)
      }

      let depth = this._baseDepth + terrainVariation + seamount
      depth = Math.max(10, depth)

      const range = depth / Math.cos(beamAngleRad)
      const acrossTrack = range * Math.sin(beamAngleRad)

      const x = acrossTrack + shipDriftX
      const y = shipDriftY
      const z = depth

      const intensity = -30 + 20 * Math.cos(beamAngleRad) + (Math.random() - 0.5) * 5

      points.push({ x, y, z, intensity, beamIndex: i, timestamp })
    }

    return {
      timestamp,
      pingNumber,
      heading: (pingNumber * 0.1) % 360,
      points
    }
  }
}
