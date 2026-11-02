package main

import (
	"go/ast"
	"testing"
)

func TestPropertyName(t *testing.T) {
	cases := map[string]string{
		"ID":                         "id",
		"SendIntentID":               "sendIntentId",
		"VTXOs":                      "vtxos",
		"RecoveredVTXOs":             "recoveredVtxos",
		"RecoveredBoardingUTXOs":     "recoveredBoardingUtxos",
		"CSV":                        "csv",
		"CPFPFeeSat":                 "cpfpFeeSat",
		"VTXOAmountSat":              "vtxoAmountSat",
		"ActorID":                    "actorId",
		"IdentityPubKey":             "identityPubKey",
		"FeeRateSatPerVByte":         "feeRateSatPerVByte",
		"UsableFeeUTXOCount":         "usableFeeUtxoCount",
		"RecoveredOORReceiveScripts": "recoveredOorReceiveScripts",
		"MaxPaymentCLTV":             "maxPaymentCltv",
		"VTXOOutpoint":               "vtxoOutpoint",
		"BlockHeight":                "blockHeight",
		"Txid":                       "txid",
	}
	for in, want := range cases {
		if got := propertyName(in); got != want {
			t.Errorf("propertyName(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestEnumEntryName(t *testing.T) {
	cases := [][3]string{
		{"ExitJobStatus", "ExitJobStatusCSVPending", "CSV_PENDING"},
		{"ListView", "ListViewVTXOs", "VTXOS"},
		{"SendRail", "SendRailInArk", "IN_ARK"},
		{"WalletState", "WalletStateNone", "NONE"},
		{"EntryFailureCode", "EntryFailureCodeNeedsIntervention", "NEEDS_INTERVENTION"},
	}
	for _, c := range cases {
		if got := enumEntryName(c[0], c[1]); got != c[2] {
			t.Errorf("enumEntryName(%q) = %q, want %q", c[1], got, c[2])
		}
	}
}

func TestIsRequest(t *testing.T) {
	g := &generator{structs: map[string]*structDef{}, enums: map[string]*enumDef{}}
	g.structs["Entry"] = &structDef{name: "Entry", fields: []fieldDef{
		{goName: "Request", typ: &ast.StarExpr{X: ast.NewIdent("EntryRequest")}},
	}}
	g.structs["EntryRequest"] = &structDef{name: "EntryRequest"}
	g.structs["ListRequest"] = &structDef{name: "ListRequest"}
	if g.isRequest("EntryRequest") {
		t.Error("EntryRequest is nested in Entry, so it is a model type")
	}
	if !g.isRequest("ListRequest") {
		t.Error("ListRequest is a verb request")
	}
}
