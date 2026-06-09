<template>
  <div class="depth-map-container" ref="container">
    <canvas ref="canvas"></canvas>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { HeightmapRenderer } from '../gl/HeightmapRenderer.js'
import { WebSocketClient } from '../services/WebSocketClient.js'
import { SonarSimulatorFrontend } from '../services/SonarSimulatorFrontend.js'

const emit = defineEmits(['status-change', 'frame-update'])

const container = ref(null)
const canvas = ref(null)

let renderer = null
let wsClient = null
let simulator = null
let isDragging = false
let lastMouseX = 0
let lastMouseY = 0

function onMouseDown(e) {
  if (e.button === 0) {
    isDragging = true
    lastMouseX = e.clientX
    lastMouseY = e.clientY
    e.preventDefault()
  }
}

function onMouseMove(e) {
  if (!isDragging || !renderer) return
  const dx = e.clientX - lastMouseX
  const dy = e.clientY - lastMouseY
  lastMouseX = e.clientX
  lastMouseY = e.clientY

  renderer.azimuth -= dx * 0.005
  renderer.elevation += dy * 0.005
  renderer.elevation = Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, renderer.elevation))
  renderer.dirty = true
}

function onMouseUp() {
  isDragging = false
}

function onWheel(e) {
  if (!renderer) return
  e.preventDefault()
  const delta = e.deltaY > 0 ? 1.1 : 0.9
  renderer.distance *= delta
  renderer.distance = Math.max(0.5, Math.min(20, renderer.distance))
  renderer.dirty = true
}

function onKeyDown(e) {
  if (!renderer) return
  if (e.key === 'r' || e.key === 'R') {
    renderer.resetCamera()
  } else if (e.key === 'w' || e.key === 'W') {
    renderer.wireframe = !renderer.wireframe
    renderer.dirty = true
  }
}

function onFrame(frame) {
  if (renderer) renderer.updateDepthData(frame)
  const stats = {
    pingCount: frame.pingNumber || 0,
    beamCount: frame.points ? frame.points.length : 0,
    depthMin: 0,
    depthMax: 0
  }
  if (frame.points && frame.points.length > 0) {
    let minZ = Infinity, maxZ = -Infinity
    for (const p of frame.points) {
      if (p.z < minZ) minZ = p.z
      if (p.z > maxZ) maxZ = p.z
    }
    stats.depthMin = minZ
    stats.depthMax = maxZ
  }
  emit('frame-update', stats)
}

function onStatusChange(status) {
  emit('status-change', status)
}

onMounted(() => {
  renderer = new HeightmapRenderer(canvas.value)
  renderer.start()

  wsClient = new WebSocketClient('/ws')
  wsClient.onFrame = onFrame
  wsClient.onStatusChange = (status) => {
    onStatusChange(status)
    if (status && !simulator) {
      return
    }
  }
  wsClient.connect()

  simulator = new SonarSimulatorFrontend(onFrame)
  const wsOriginalOnClose = wsClient._tryConnect.bind(wsClient)

  setTimeout(() => {
    if (!wsClient.isConnected) {
      simulator.start()
      onStatusChange(false)
    }
  }, 2000)

  const el = container.value
  el.addEventListener('mousedown', onMouseDown)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
  el.addEventListener('wheel', onWheel, { passive: false })
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  const el = container.value
  el.removeEventListener('mousedown', onMouseDown)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
  el.removeEventListener('wheel', onWheel)
  window.removeEventListener('keydown', onKeyDown)

  if (simulator) {
    simulator.stop()
    simulator = null
  }
  if (wsClient) {
    wsClient.disconnect()
    wsClient = null
  }
  if (renderer) {
    renderer.destroy()
    renderer = null
  }
})
</script>

<style scoped>
.depth-map-container {
  width: 100%;
  height: 100%;
  position: relative;
  cursor: grab;
}

.depth-map-container:active {
  cursor: grabbing;
}

canvas {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
