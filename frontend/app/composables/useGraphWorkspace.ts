import { computed, ref } from 'vue'

type ApiCanvas = { id: number; project_id?: number; projectId?: number; graph_kind?: string; graphKind?: string }
type ApiNode = Record<string, any> & { id: number; node_key?: string; nodeKey?: string }
type ApiLink = Record<string, any> & { id: number; link_key?: number; linkKey?: number }
type ApiGroup = Record<string, any> & { id: number; group_key?: number; groupKey?: number }

export const useGraphWorkspace = () => {
  const http = useHttp()
  const graphId = ref<number | null>(null)
  const loading = ref(false)
  const supported = ref(false)
  const error = ref<string | null>(null)
  const linkIds = new Map<number, number>()

  async function load(projectId: string, episodeId?: string) {
    const id = Number(projectId)
    if (!Number.isInteger(id) || id <= 0) return null
    supported.value = true
    loading.value = true
    error.value = null
    try {
      const numericEpisodeId = Number(episodeId)
      const result = await http.get<{ list: ApiCanvas[] }>('/graphs', {
        project_id: id,
        ...(Number.isInteger(numericEpisodeId) && numericEpisodeId > 0 ? { episode_id: numericEpisodeId } : {}),
      })
      let canvas = result.list?.[0]
      if (!canvas) {
        canvas = await http.post<ApiCanvas>('/graphs', {
          projectId: id,
          ...(Number.isInteger(numericEpisodeId) && numericEpisodeId > 0 ? { episodeId: numericEpisodeId } : {}),
          graphKind: 'ROOT',
        }, { payloadMode: 'json' })
      }
      graphId.value = canvas.id
      const detail = await http.get<{ canvas: ApiCanvas; nodes: ApiNode[]; links: ApiLink[]; groups: ApiGroup[] }>(`/graphs/${canvas.id}`)
      linkIds.clear()
      for (const link of detail.links || []) {
        const linkKey = Number(link.linkKey ?? link.link_key)
        if (Number.isFinite(linkKey) && link.id != null) linkIds.set(linkKey, link.id)
      }
      return detail
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '画布加载失败'
      return null
    } finally {
      loading.value = false
    }
  }

  async function saveNode(node: any) {
    if (!graphId.value) return null
    const payload = {
      nodeKey: node.id, nodeType: node.type, title: node.title, positionX: node.x, positionY: node.y,
      width: node.width, height: node.height, executionOrder: node.order, mode: node.mode === '禁用' ? 4 : node.mode === '旁路' ? 2 : 0,
      color: node.color, inputs: node.inputs, outputs: node.outputs,
    }
    const existing = await http.get<{ list: ApiNode[] }>(`/graphs/${graphId.value}/nodes`)
    const found = existing.list?.find((item) => (item.nodeKey ?? item.node_key) === node.id)
    if (found) return http.put<ApiNode>(`/graphs/${graphId.value}/nodes/${found.id}`, payload, { payloadMode: 'json' })
    return http.post<ApiNode>(`/graphs/${graphId.value}/nodes`, payload, { payloadMode: 'json' })
  }

  async function saveGroup(group: any) {
    if (!graphId.value) return null
    const payload = { groupKey: Number(group.id.replace(/\D/g, '')) || undefined, title: group.title, positionX: group.x, positionY: group.y, width: group.width, height: group.height }
    const existing = await http.get<{ list: ApiGroup[] }>(`/graphs/${graphId.value}/groups`)
    const found = existing.list?.find((item) => (item.groupKey ?? item.group_key) === payload.groupKey)
    if (found) return http.put<ApiGroup>(`/graphs/${graphId.value}/groups/${found.id}`, payload, { payloadMode: 'json' })
    return http.post<ApiGroup>(`/graphs/${graphId.value}/groups`, payload, { payloadMode: 'json' })
  }

  async function saveLink(link: any, nodes: any[]) {
    if (!graphId.value) return null
    const from = nodes.find((node) => node.id === link.from)
    const to = nodes.find((node) => node.id === link.to)
    if (!from || !to) return null
    const payload = { linkKey: Number(link.id), originNodeKey: from.id, originSlot: link.fromIndex, targetNodeKey: to.id, targetSlot: link.toIndex, floating: false }
    const existing = await http.get<{ list: ApiLink[] }>(`/graphs/${graphId.value}/links`)
    const found = existing.list?.find((item) => (item.linkKey ?? item.link_key) === payload.linkKey)
    if (found) {
      linkIds.set(payload.linkKey, found.id)
      return http.put<ApiLink>(`/graphs/${graphId.value}/links/${found.id}`, payload, { payloadMode: 'json' })
    }
    const saved = await http.post<ApiLink>(`/graphs/${graphId.value}/links`, payload, { payloadMode: 'json' })
    if (saved?.id != null) linkIds.set(payload.linkKey, saved.id)
    return saved
  }

  async function deleteLink(link: any) {
    if (!graphId.value) return
    const linkKey = Number(link.id)
    let serverId = Number(link.serverId)
    if (!Number.isInteger(serverId) || serverId <= 0) serverId = linkIds.get(linkKey) || 0
    if (!serverId) {
      const result = await http.get<{ list: ApiLink[] }>(`/graphs/${graphId.value}/links`)
      const found = result.list?.find((item) => Number(item.linkKey ?? item.link_key) === linkKey)
      serverId = found?.id || 0
    }
    if (serverId) {
      await http.delete(`/graphs/${graphId.value}/links/${serverId}`)
      linkIds.delete(linkKey)
    }
  }

  async function saveAll(nodes: any[], links: any[], groups: any[]) {
    if (!graphId.value) return
    await Promise.all([...nodes.map(saveNode), ...groups.map(saveGroup), ...links.map((link) => saveLink(link, nodes))])
  }

  async function deleteNode(node: any) {
    if (!graphId.value) return
    const result = await http.get<{ list: ApiNode[] }>(`/graphs/${graphId.value}/nodes`)
    const found = result.list?.find((item) => (item.nodeKey ?? item.node_key) === node.id)
    if (found) await http.delete(`/graphs/${graphId.value}/nodes/${found.id}`)
  }

  async function deleteLinksForNode(nodeId: string) {
    if (!graphId.value) return
    const result = await http.get<{ list: ApiLink[] }>(`/graphs/${graphId.value}/links`)
    const related = (result.list || []).filter((item) => String(item.originNodeKey ?? item.origin_node_key) === String(nodeId) || String(item.targetNodeKey ?? item.target_node_key) === String(nodeId))
    await Promise.all(related.map((link) => http.delete(`/graphs/${graphId.value}/links/${link.id}`)))
  }

  async function deleteGroup(group: any) {
    if (!graphId.value) return
    const id = Number(String(group.id).replace(/\D/g, ''))
    if (Number.isInteger(id) && id > 0) await http.delete(`/graphs/${graphId.value}/groups/${id}`)
  }

  const ready = computed(() => supported.value && graphId.value != null)
  return { graphId, loading, error, ready, load, saveNode, saveGroup, saveLink, deleteLink, saveAll, deleteNode, deleteLinksForNode, deleteGroup }
}
