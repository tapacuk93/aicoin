package store

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"aicoin/internal/chain"
)

// chainID is the fixed partition key value CONTRACT.md's "Persistence"
// section specifies: one chain, one partition, always the literal string
// "aicoin".
const chainID = "aicoin"

// item mirrors the DynamoDB item shape from CONTRACT.md's "Persistence"
// section: one item per block, with the block's Transactions JSON-encoded
// into a single string attribute (transactions_json) so an item never needs
// to hold the whole chain (which would eventually exceed DynamoDB's 400KB
// per-item limit as the chain grows).
type item struct {
	ChainID          string `dynamodbav:"chain_id"`
	BlockIndex       int    `dynamodbav:"block_index"`
	Timestamp        string `dynamodbav:"timestamp"`
	PrevHash         string `dynamodbav:"prev_hash"`
	Hash             string `dynamodbav:"hash"`
	Signature        string `dynamodbav:"signature"`
	TransactionsJSON string `dynamodbav:"transactions_json"`
}

// DynamoDB is a chain.ChainStore backed by a real AWS DynamoDB table, per
// CONTRACT.md's "Persistence (DynamoDB — genuinely durable, not a cache)"
// section. It is the only file in this package that imports a DynamoDB
// client, so the dependency stays contained here.
type DynamoDB struct {
	client *dynamodb.Client
	table  string
}

// NewDynamoDB creates a DynamoDB-backed ChainStore talking to table, using
// the AWS SDK's standard default credential/config chain (env vars,
// instance role, shared config, etc. — see config.LoadDefaultConfig). No
// separate region/credential flags are needed; the environment is expected
// to already have those set up (e.g. AWS_REGION plus an instance role or
// AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY).
func NewDynamoDB(ctx context.Context, table string) (*DynamoDB, error) {
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		return nil, fmt.Errorf("store: loading default AWS config: %w", err)
	}
	return &DynamoDB{client: dynamodb.NewFromConfig(cfg), table: table}, nil
}

// Load runs a Query for chain_id = "aicoin", sorted ascending by
// block_index (DynamoDB's default sort-key order, i.e. no
// ScanIndexForward:false needed), paginating through the whole result set,
// and unmarshals each item back into a chain.Block — JSON-decoding
// transactions_json back into []chain.Transaction. If the table has no
// items yet, it returns (nil, nil), per the chain.ChainStore contract
// ("nothing persisted yet").
func (d *DynamoDB) Load() ([]chain.Block, error) {
	ctx := context.Background()

	keyCond := "chain_id = :cid"
	exprValues, err := attributevalue.MarshalMap(map[string]string{":cid": chainID})
	if err != nil {
		return nil, fmt.Errorf("store: marshaling query key condition: %w", err)
	}

	var blocks []chain.Block
	var lastKey map[string]types.AttributeValue
	for {
		out, err := d.client.Query(ctx, &dynamodb.QueryInput{
			TableName:                 aws.String(d.table),
			KeyConditionExpression:    aws.String(keyCond),
			ExpressionAttributeValues: exprValues,
			ExclusiveStartKey:         lastKey,
		})
		if err != nil {
			return nil, fmt.Errorf("store: dynamodb Query on table %s: %w", d.table, err)
		}
		for _, av := range out.Items {
			var it item
			if err := attributevalue.UnmarshalMap(av, &it); err != nil {
				return nil, fmt.Errorf("store: unmarshaling dynamodb item: %w", err)
			}
			b, err := itemToBlock(it)
			if err != nil {
				return nil, err
			}
			blocks = append(blocks, b)
		}
		if len(out.LastEvaluatedKey) == 0 {
			break
		}
		lastKey = out.LastEvaluatedKey
	}
	return blocks, nil
}

// AppendBlock PutItems exactly one block's item — an O(1) write regardless
// of chain length, per CONTRACT.md's "Persistence" section.
func (d *DynamoDB) AppendBlock(b chain.Block) error {
	ctx := context.Background()

	it, err := blockToItem(b)
	if err != nil {
		return err
	}
	av, err := attributevalue.MarshalMap(it)
	if err != nil {
		return fmt.Errorf("store: marshaling block %d for dynamodb: %w", b.Index, err)
	}
	if _, err := d.client.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(d.table),
		Item:      av,
	}); err != nil {
		return fmt.Errorf("store: dynamodb PutItem block %d on table %s: %w", b.Index, d.table, err)
	}
	return nil
}

// blockToItem converts a chain.Block into the DynamoDB item shape,
// JSON-encoding its Transactions into the transactions_json string
// attribute.
func blockToItem(b chain.Block) (item, error) {
	txJSON, err := json.Marshal(b.Transactions)
	if err != nil {
		return item{}, fmt.Errorf("store: marshaling transactions for block %d: %w", b.Index, err)
	}
	return item{
		ChainID:          chainID,
		BlockIndex:       b.Index,
		Timestamp:        b.Timestamp,
		PrevHash:         b.PrevHash,
		Hash:             b.Hash,
		Signature:        b.Signature,
		TransactionsJSON: string(txJSON),
	}, nil
}

// itemToBlock converts a DynamoDB item back into a chain.Block,
// JSON-decoding transactions_json back into []chain.Transaction.
func itemToBlock(it item) (chain.Block, error) {
	var txs []chain.Transaction
	if it.TransactionsJSON != "" {
		if err := json.Unmarshal([]byte(it.TransactionsJSON), &txs); err != nil {
			return chain.Block{}, fmt.Errorf("store: unmarshaling transactions_json for block %d: %w", it.BlockIndex, err)
		}
	}
	return chain.Block{
		Index:        it.BlockIndex,
		Timestamp:    it.Timestamp,
		PrevHash:     it.PrevHash,
		Hash:         it.Hash,
		Signature:    it.Signature,
		Transactions: txs,
	}, nil
}
