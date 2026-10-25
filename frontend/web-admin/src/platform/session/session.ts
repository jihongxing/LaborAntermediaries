import { ref } from 'vue'

const token = ref('')
const organizationId = ref('')

export function useSession() {
  function setToken(value: string) {
    token.value = value.trim()
  }

  function setOrganizationId(value: string) {
    organizationId.value = value.trim()
  }

  function clear() {
    token.value = ''
    organizationId.value = ''
  }

  return { token, organizationId, setToken, setOrganizationId, clear }
}
