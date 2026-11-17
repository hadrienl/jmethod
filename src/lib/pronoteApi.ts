export async function pronoteApi<T>(
  path: string,
  payload?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/pronote/${path}`, {
      method: payload === undefined ? "GET" : "POST",
      headers: {
        "X-JMethod-Client": "local-poc",
        ...(payload !== undefined
          ? { "Content-Type": "application/json" }
          : {}),
      },
      credentials: "same-origin",
      body: payload === undefined ? undefined : JSON.stringify(payload),
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new Error(
      "Le service de connexion local est indisponible. Relance le POC, puis réessaie.",
    );
  }
  let data: T & { error?: string };
  try {
    data = await response.json();
  } catch {
    throw new Error(
      "Le service de connexion local est indisponible. Relance le POC, puis réessaie.",
    );
  }
  if (!response.ok)
    throw new Error(data.error ?? "La connexion Pronote a échoué.");
  return data;
}
