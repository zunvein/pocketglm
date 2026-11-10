package zbridge

import (
	"fmt"
	"strings"
)

// printBanner prints the startup summary as one box, every row padded to the
// same width. Rows are plain ASCII, so byte length equals column width.
func printBanner(port int, maskedKey string) {
	const inner = 54
	rows := []string{
		"PocketGLM bridge is running",
		"",
		fmt.Sprintf("Health      http://127.0.0.1:%d/health", port),
		fmt.Sprintf("OpenAI      http://127.0.0.1:%d/v1", port),
		fmt.Sprintf("Anthropic   http://127.0.0.1:%d", port),
		fmt.Sprintf("API key     %s", maskedKey),
	}
	line := func(s string) string {
		pad := inner - 2 - len(s)
		if pad < 0 {
			pad = 0
		}
		return "| " + s + strings.Repeat(" ", pad) + " |"
	}
	edge := "+" + strings.Repeat("-", inner) + "+"
	fmt.Println()
	fmt.Println(edge)
	for _, r := range rows {
		fmt.Println(line(r))
	}
	fmt.Println(edge)
	fmt.Println()
}
