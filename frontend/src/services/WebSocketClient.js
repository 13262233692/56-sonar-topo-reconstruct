export class WebSocketClient {
  constructor(url = '/ws') {
    this.url = url
    this.ws = null
    this.onFrame = null
    this.onStatusChange = null
    this._reconnectTimer = null
    this._reconnectDelay = 1000
    this._maxReconnectDelay = 16000
    this._disposed = false
  }

  get isConnected() {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN
  }

  connect() {
    this._disposed = false
    this._tryConnect()
  }

  disconnect() {
    this._disposed = true
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer)
      this._reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this._notifyStatus(false)
  }

  _tryConnect() {
    if (this._disposed) return

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const url = this.url.startsWith('ws') ? this.url : `${protocol}//${host}${this.url}`

    try {
      this.ws = new WebSocket(url)
    } catch {
      this._scheduleReconnect()
      return
    }

    this.ws.onopen = () => {
      this._reconnectDelay = 1000
      this._notifyStatus(true)
    }

    this.ws.onmessage = (event) => {
      try {
        const frame = JSON.parse(event.data)
        if (this.onFrame) this.onFrame(frame)
      } catch {}
    }

    this.ws.onclose = () => {
      this._notifyStatus(false)
      this.ws = null
      this._scheduleReconnect()
    }

    this.ws.onerror = () => {
      this._notifyStatus(false)
    }
  }

  _scheduleReconnect() {
    if (this._disposed) return
    if (this._reconnectTimer) return

    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null
      this._tryConnect()
    }, this._reconnectDelay)

    this._reconnectDelay = Math.min(this._reconnectDelay * 2, this._maxReconnectDelay)
  }

  _notifyStatus(status) {
    if (this.onStatusChange) this.onStatusChange(status)
  }
}
