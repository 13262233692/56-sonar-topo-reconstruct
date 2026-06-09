<template>
  <div class="app-container">
    <div class="overlay-bar">
      <div class="status-group">
        <span class="status-dot" :class="connected ? 'connected' : 'disconnected'"></span>
        <span class="status-text">{{ connected ? '已连接' : '未连接' }}</span>
      </div>
      <div class="info-item">
        <span class="info-label">Ping</span>
        <span class="info-value">{{ pingCount }}</span>
      </div>
      <div class="info-item">
        <span class="info-label">Beam</span>
        <span class="info-value">{{ beamCount }}</span>
      </div>
      <div class="info-item">
        <span class="info-label">深度范围</span>
        <span class="info-value">{{ depthMin.toFixed(1) }}m - {{ depthMax.toFixed(1) }}m</span>
      </div>
    </div>
    <DepthMap
      :ping-count="pingCount"
      :beam-count="beamCount"
      :depth-min="depthMin"
      :depth-max="depthMax"
      @status-change="onStatusChange"
      @frame-update="onFrameUpdate"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DepthMap from './components/DepthMap.vue'

const connected = ref(false)
const pingCount = ref(0)
const beamCount = ref(0)
const depthMin = ref(0)
const depthMax = ref(0)

function onStatusChange(status) {
  connected.value = status
}

function onFrameUpdate(frame) {
  if (frame.pingCount !== undefined) pingCount.value = frame.pingCount
  if (frame.beamCount !== undefined) beamCount.value = frame.beamCount
  if (frame.depthMin !== undefined) depthMin.value = frame.depthMin
  if (frame.depthMax !== undefined) depthMax.value = frame.depthMax
}
</script>

<style>
.app-container {
  width: 100vw;
  height: 100vh;
  background: #0a0e17;
  position: relative;
  overflow: hidden;
}

.overlay-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 10px 20px;
  background: rgba(10, 14, 23, 0.85);
  border-bottom: 1px solid rgba(64, 156, 255, 0.2);
  backdrop-filter: blur(8px);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: #c0d8f0;
}

.status-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  transition: background 0.3s;
}

.status-dot.connected {
  background: #00e676;
  box-shadow: 0 0 8px rgba(0, 230, 118, 0.6);
}

.status-dot.disconnected {
  background: #ff1744;
  box-shadow: 0 0 8px rgba(255, 23, 68, 0.6);
}

.status-text {
  font-weight: bold;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.info-label {
  color: #6090b0;
  font-size: 11px;
  text-transform: uppercase;
}

.info-value {
  color: #40b0ff;
  font-weight: bold;
}
</style>
