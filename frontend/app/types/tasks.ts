export interface TaskEvent {
  id: number
  event_type: string
  from_status: string | null
  to_status: string | null
  stage: string | null
  progress: number | null
  message_code: string | null
  created_at: string | null
}

export interface TaskItem {
  id: number
  project_id: number
  episode_id: number | null
  project_version_id?: number | null
  task_type: string
  title: string
  status: string
  progress: number
  progress_indeterminate: boolean
  stage: string | null
  provider_config_id?: number | null
  model_name: string | null
  input_summary?: string | null
  output_file_ids?: number[]
  created_at: string | null
  queued_at?: string | null
  started_at: string | null
  completed_at: string | null
  error_code: string | null
  error_message: string | null
  attempt: number
  max_attempts: number
  parent_task_id?: number | null
  cancel_requested_at?: string | null
  cancelled_by?: number | null
  cancel_reason?: string | null
  can_cancel: boolean
  events?: TaskEvent[]
}

export interface TaskPage {
  list: TaskItem[]
  page: number
  page_size: number
  total: number
  counts: Record<string, number>
}
