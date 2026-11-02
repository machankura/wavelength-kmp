// Command go-fixtures prints golden encoding/json output for the wavelength
// SDK DTOs (see README.md). types.go is generated next to it.
package main

import (
	"encoding/json"
	"fmt"
	"time"
)

func dump(name string, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	fmt.Printf("%s\t%s\n", name, b)
}

func main() {
	ts := time.Date(2026, 11, 2, 10, 30, 0, 123456789, time.FixedZone("SAST", 2*3600))
	dump("info", Info{Version: "0.2.1", Commit: "76fabd09", Network: "signet", BlockHeight: 4294967295, ServerConnected: true, WalletType: "lwwallet", WalletState: WalletStateReady, IdentityPubKey: "02abc", ServerInfo: &ServerInfo{FreeRefreshWindowBlocks: 144}})
	dump("info_zero", Info{})
	dump("balance", Balance{ConfirmedSat: 125000, PendingInSat: 10000, PendingOutSat: 0, CreditAvailableSat: 500, CreditReservedSat: 1})
	dump("create_wallet_result", CreateWalletResult{Mnemonic: []string{"abandon", "ability"}, EncipheredSeed: []byte{0, 1, 2, 250}, IdentityPubKey: "02abc", RecoveryRan: true, RecoveredVTXOs: 3})
	dump("entry_send", Entry{ID: "send-1", Kind: EntryKindSend, Status: EntryStatusComplete, AmountSat: 1000, FeeSat: 2, Counterparty: "lnbc1...", CreatedAt: ts, UpdatedAt: ts, Cursor: 42,
		Progress: &EntryProgress{Phase: EntryPhaseConfirmed, PhaseLabel: "Paid", PaymentHash: "ph", Preimage: "pre"},
		Request:  &EntryRequest{Type: EntryRequestTypeLightning, LightningInvoice: "lnbc1...", PaymentHash: "ph"}})
	dump("entry_minimal", Entry{ID: "r", Kind: EntryKindReceive, Status: EntryStatusPending})
	dump("entry_unknown_kind", Entry{ID: "x", Kind: EntryKind("teleport"), Status: EntryStatusFailed, FailureCode: EntryFailureCodeNeedsIntervention})
	dump("prepare_send_result", PrepareSendResult{SendIntentID: "intent-1", AmountSat: 5000, ExpectedFeeSat: 10, FeeKnown: true, Rail: SendRailLightning, QuoteStatus: SendQuoteStatusComplete, PaymentHash: "ph", ExpiresAtUnix: 1800000000, CreditPreview: &CreditPreview{CreditAppliedSat: 1}})
	dump("list_vtxos", ListResult{View: ListViewVTXOs, VTXOs: &VTXOInventory{VTXOs: []WalletVTXO{{Outpoint: "txid:0", AmountSat: 21000, Status: "live", BatchExpiry: 900000, CommitmentTxid: "c"}}, Total: 1}})
	dump("list_activity_empty", ListResult{View: ListViewActivity, Activity: &ActivityList{}})
	dump("exit_status", ExitStatusResult{Found: true, Status: ExitJobStatusCSVPending, CSV: &ExitCSV{BlocksRemaining: 12}, Fees: &ExitFees{CPFPFeeSat: 300, VTXOAmountSat: 10000}, Progress: &ExitProgress{TotalLayers: 2}})
	dump("exit_result", ExitResult{Path: ExitPathCooperative, Cooperative: true, QueuedOutpoints: []string{"txid:0"}, ActorID: "actor"})
	dump("status", Status{Ready: true, Unlocked: true, Network: "signet", Balance: Balance{ConfirmedSat: 1}, PendingCount: 2})
	dump("onchain_tx_zero_time", OnchainTx{Txid: "t", Kind: "boarding"})
	// Requests: what Go would emit for these DTOs, i.e. the keys it expects.
	dump("req_create_wallet", CreateWalletRequest{WalletPassword: []byte("pw"), RecoveryWindow: 2500})
	dump("req_list", ListRequest{View: ListViewActivity, Kinds: []EntryKind{EntryKindSend}, Limit: 50, Cursor: "c1"})
	dump("req_subscribe", SubscribeRequest{IncludeExisting: true, Cursor: 7})
	dump("req_send_prepared", SendPreparedRequest{SendIntentID: "intent-1"})
	dump("req_exit_summary", ExitSummaryRequest{})
}
